# Web版 Postgres → Firestore 移行 設計書

## 1. 背景・目的

`docs/android-app-design.md` 5節の通り、Android版はクラウドバックアップ先をFirestoreに変更済み。
本書はその続き（同ドキュメント3.2「Web側もFirestoreへ統一するかは次フェーズで検討する」）として、
**Web版（Next.js）のデータストアをPostgresからFirestoreへ移行する**設計を扱う。

## 2. 方針: 最小変更（DBアクセス層のみ差し替え）

Android版はFirestore移行と同時に `users/{uid}` 単位の非共有モデル・Firebase Auth (Google Sign-In) に
切り替えたが、Web版では以下の理由から **認証方式とマルチテナンシー設計は変更しない**。

- Web版は既存のOIDC (Pocket ID) ログイン + `app_users`(email主キー) + `user_subscriptions` による
  「同じ`target_accounts`を複数ユーザーが共有購読する」モデルで、クロール・X API課金が1回で済む
  設計になっている。Android版の`users/{uid}`非共有モデルに合わせると、複数ユーザーが同じアカウントを
  推す場合にクロールが重複し、API課金が人数分に増えてしまう。
- 依頼内容は「DBアクセス実装の変更」であり、認証基盤の刷新は要求されていない。
- そのため、Web版とAndroid版はFirestore上でも別々のコレクションツリーを使う
  （Web: トップレベルの`target_accounts`等、Android: `users/{uid}/targetAccounts`等）。
  同じFirebaseプロジェクトを共用しても構わないが、データは共有されない
  （元々Postgres時代から別データソースだったため、この点は退行ではない）。
- Web側はNext.jsのRoute Handler（サーバー専用）からFirebase **Admin SDK** でのみFirestoreにアクセスする。
  Admin SDKはセキュリティルールを経由しないため、Firestoreセキュリティルールの追加は不要
  （Postgresを外部公開していなかったのと同じく、クライアントから直接Firestoreを触らせない）。

## 3. コレクション設計

Postgresのテーブル・カラム名をできるだけそのまま踏襲し（`snake_case`）、移行に伴う意味的な変更を
最小化する。ドキュメントIDは元の主キーに対応させる。

| コレクション | ドキュメントID | 対応する旧テーブル |
|---|---|---|
| `app_users` | `email` | `app_users` |
| `target_accounts` | `screen_name` | `target_accounts` |
| `user_subscriptions` | `${user_email}::${screen_name}` | `user_subscriptions` |
| `media_assets` | `media_key` | `media_assets` |
| `share_links` | `token` | `share_links` |
| `api_usage_log` | 自動ID | `api_usage_log` |

複合主キーだった`user_subscriptions`は、決定的なドキュメントID（`email::screen_name`）にすることで
「同じ組み合わせで2回書いても1件のまま」というON CONFLICT DO NOTHING相当の冪等性を保つ
（IDから逆算はせず、`user_email`/`screen_name`は別途フィールドとしても持たせて検索に使う）。

### 3.1 フィールド差分・注意点

- `posted_at`, `created_at`, `checked_at`, `called_at`, `revoked_at` は Firestore の `Timestamp` 型で保持する。
- Postgresの`NULL`は「フィールドを明示的に`null`値として書き込む」ことで表現する
  （フィールド自体を省略すると`== null`の等価フィルタにヒットしないため）。
- `media_assets.r2_backup_url IS NULL` の絞り込みが多用されているため、判定用に
  `backed_up: boolean` フィールドを追加する（`r2_backup_url`が入った時点で`true`にする）。
  Firestoreの複合クエリは等価条件を並べる方が組みやすく、インデックス設計もシンプルになるため。
- `embedding vector(512)` は現状未使用（将来のCLIP検索用）。Firestoreにも同名で
  `embedding: number[] | null` として持たせておくが、ベクトル検索インデックスは今回設定しない
  （必要になった時点でFirestore Vector Search用の複合インデックスを追加する）。
- `ml_tags jsonb` → `ml_tags: object | null` としてそのまま格納（Firestoreはネストしたmapを扱える）。

### 3.2 カスケード削除の手動実装

Postgresの`ON DELETE CASCADE`はFirestoreに存在しないため、アプリケーション側で明示的に実装する。

- `target_accounts`削除時 → その`x_user_id`に紐づく`media_assets`と、その`screen_name`に紐づく
  `share_links`をクエリ→バッチ削除（500件ずつ）してから本体を削除する。
- `app_users`削除は現状どの画面からも呼ばれない（退会機能なし）ため、移行対象としては
  何もしない（Postgres側も同様に未実装）。

## 4. クエリ実装方針（SQLからの主な置き換えパターン）

### 4.1 集計 (`count(*)`)

Firestoreの集計クエリ（`count()` / `sum()` / `average()`）を使う。ドキュメント件数に関わらず
1回の集計クエリとして課金されるため、`media_assets`のような大きいコレクションのcountにも使える。
ただし **`min()`/`max()`はFirestore集計クエリに存在しない**。

### 4.2 `MIN(tweet_id::bigint)`（バックフィル起点のフォールバック）

XのTweet IDはSnowflake形式で生成時刻とほぼ単調増加の関係にあるため、
「`posted_at`昇順で1件取得しその`tweet_id`を使う」で代替する
（`backfill_cursor`が既にある通常経路では使われないフォールバックのみに影響）。

### 4.3 `UPDATE ... WHERE ...`（一括更新）

Firestoreに条件付き一括UPDATEはないため「クエリして該当ドキュメントIDを集め、
500件ずつのバッチで`.update()`」という2段階に分解する（例: `/api/reveal`）。

### 4.4 `/api/usage` の集計（`generate_series` + `GROUP BY`）

Firestoreにはgenerate_seriesも日付バケットのGROUP BYもない。以下のハイブリッドで実装する。

- 当日/当月/全期間の合計・件数: Firestore集計クエリ（`sum`/`count`）を条件ごとに発行
  （呼び出し回数は少なく、都度1読み取り課金で済む）。
- 直近30日の日次内訳・直近12ヶ月の月次内訳・用途別内訳: 直近12ヶ月分の生ドキュメントを
  1回のクエリで取得し、アプリケーション側（JS）で日付バケットにグルーピングする
  （このアプリの呼び出し頻度＝個人〜友人規模を前提とすれば、12ヶ月分でも数千件程度に収まり、
  Firestoreの無料枠内で現実的な範囲）。
- 直近50件 (`recent`): `orderBy(called_at desc).limit(50)` にそのまま置き換え。

### 4.5 ページネーション

`(posted_at, media_key) < (cursorPostedAt, cursorMediaKey)` によるキーセットページネーションは
Firestoreの複合`orderBy(posted_at desc, media_key desc)` + `startAfter(cursorPostedAt, cursorMediaKey)`
でそのまま表現できる（両方とも降順soのため、タプル比較の意味が一致する）。

### 4.6 `x_user_id = ANY($1::text[])`（複数アカウント絞り込み）

Firestoreの`where('x_user_id', 'in', [...])`に置き換える。**上限30件**という制約があるため、
ユーザーの推しリストが30アカウントを超えるケースでは30件にトリムする
（現実的な利用規模ではまず超えない想定。超えた場合は先頭30件のみ絞り込み対象になる既知の制限として
コードにコメントを残す）。

## 5. 必要な複合インデックス

`firestore.indexes.json` にまとめる（詳細は同ファイル参照）。主なもの:

- `media_assets`: (`x_user_id` IN, `revealed` ==, `posted_at` desc, `media_key` desc)
- `media_assets`: (`x_user_id` IN, `revealed` ==, `is_face` ==, `posted_at` desc, `media_key` desc)
- `media_assets`: (`x_user_id` ==, `revealed` ==, `posted_at` desc, `media_key` desc) — 共有リンク用
- `media_assets`: (`x_user_id` ==, `revealed` ==, `is_face` ==, `posted_at` desc, `media_key` desc)
- `media_assets`: (`backed_up` ==, `posted_at` desc) — バックアップ待ちキュー
- `media_assets`: (`backed_up` ==, `is_face` ==, `face_reviewed` ==, `posted_at` desc) — 顔検出待ちキュー
- `target_accounts`: (`x_user_id` != null, `backfill_done` ==)
- `share_links`: (`screen_name` ==, `revoked_at` ==, `created_at` desc)
- `user_subscriptions`: (`screen_name` ==) — 単一フィールドなので自動インデックスで足りる

## 6. データ移行（一度きり）

`frontend/scripts/migrate-postgres-to-firestore.mjs` を新設する。既存の`run-backup-face.mjs`と同様、
`@/`エイリアスに頼らず自己完結させ、フロントのDockerイメージのbuilderステージから実行する運用とする。

- 6テーブルを`pg`で読み出し、Firestore Admin SDKでコレクションに書き込む。
- `media_assets`のような大きいテーブルはカーソルで区切って読み、500件ずつバッチwriteする。
- 再実行しても安全なように、書き込みは`set()`（ドキュメントIDが主キーそのまま）で冪等にする。
- 移行後、件数（Postgres側`count(*)` vs Firestore側集計クエリ）を突き合わせて出力する。
- 移行スクリプト自体はこの一度きりの用途にのみ`pg`パッケージを使う
  （アプリ本体の実行時依存からは`pg`を外し、`devDependencies`に留める）。

## 7. 環境変数の変更

`.env.example`・`docker-compose.yml`から`PG_*`を削除し、Firebase Admin SDK用の3変数を追加する。

```
FIREBASE_PROJECT_ID=
FIREBASE_CLIENT_EMAIL=
FIREBASE_PRIVATE_KEY=
```

（Firebaseコンソール → プロジェクトの設定 → サービスアカウント → 新しい秘密鍵の生成、で取得できる
JSONの`project_id`/`client_email`/`private_key`を転記する。`private_key`は改行を`\n`エスケープした
1行文字列として`.env`に保存し、読み込み時に`.replace(/\\n/g, '\n')`する）。

## 8. 影響を受けないもの

- Cloudflare R2（画像バックアップ）: 変更なし。
- 認証（Pocket ID OIDC）: 変更なし。
- X API呼び出しロジック自体: 変更なし（ログの書き込み先だけがFirestoreに変わる）。
- フロントエンド（React components / `app/(app)/*`）: APIレスポンスのJSON形状
  （`media_key`/`posted_at`等のsnake_caseキー）を一切変えないため、無変更。
