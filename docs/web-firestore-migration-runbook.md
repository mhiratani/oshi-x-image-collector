# Web版 Postgres → Firestore 移行・切り替え手順書

`docs/web-firestore-migration-design.md`（設計）を踏まえた、実際に本番のPi環境を
Postgres運用からFirestore運用へ切り替えるための実施手順。上から順に実行する。

ダウンタイムの考え方: cronによる新着収集・バックアップ・顔検出を止めている間だけ
Postgres側のデータが変化しなくなるので、その間にデータ移行〜検証〜切り替えまで行う
（数分〜十数分程度の停止を想定。同時アクセスユーザーが少ない個人〜友人規模のため許容できる）。

## 0. 前提

- 現在の本番は `docker-compose.yml` の `frontend` コンテナ1つで稼働しており、
  cronバッチ（`CRON_SCHEDULE`, デフォルト60分毎）が同一プロセス内で動いている。
- 本手順はこのリポジトリの新しいコード（Firestore対応版）を前提にしている。
  切り替え前に `git pull` 等で本手順のコードを取得済みであること。

## 1. Firebaseプロジェクトの準備

1. [Firebaseコンソール](https://console.firebase.google.com/)で新規プロジェクトを作成し、Firestoreを有効化する
   （ロケーションは一度決めると変更できないので、Piの設置国に近いリージョンを選ぶ）。
2. プロジェクトの設定 → サービスアカウント → 「新しい秘密鍵の生成」でJSONをダウンロードする。
3. JSON内の`project_id` / `client_email` / `private_key`を控えておく（後で`.env`に設定する）。
4. 複合インデックスを事前にデプロイしておく（アプリ起動後に初めてクエリが実行された時点でも
   Firestoreがエラーメッセージにインデックス作成リンクを出してくれるが、事前に作っておいた方が
   切り替え直後にエラーが出ない）。

   ```
   npm install -g firebase-tools
   firebase login
   firebase deploy --only firestore:indexes --project <FIREBASE_PROJECT_ID>
   ```

   `firestore.indexes.json` に定義した9個の複合インデックスが作成される
   （作成には数分かかることがある。Firebaseコンソールの Firestore Database → インデックス
   で `Building` → `Enabled` になるのを待つ）。

## 2. .env の更新（この時点ではPG_*も残したままでよい）

`.env` に以下を追記する（既存の`PG_*`はまだ削除しない。移行スクリプトが読み取りに使うため）。

```
FIREBASE_PROJECT_ID=<手順1で控えたproject_id>
FIREBASE_CLIENT_EMAIL=<手順1で控えたclient_email>
FIREBASE_PRIVATE_KEY=<手順1で控えたprivate_key。改行を \n にエスケープした1行文字列で>
```

`private_key`のエスケープは例えば以下のように行う（ダウンロードしたJSONから抽出する場合）。

```
node -e "console.log(JSON.stringify(require('/path/to/serviceAccount.json').private_key))"
```

出力された`"-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----\n"`のダブルクォートを外して
`.env`の`FIREBASE_PRIVATE_KEY=`の後ろにそのまま貼る。

## 3. メンテナンスモードに入る（cron停止）

新着収集中にPostgresのデータが動き続けると、後述の移行スクリプトが取りこぼす可能性があるため、
移行直前にバッチを止める。

```
docker compose stop frontend
```

（Web画面自体も止まる。停止時間を短くしたい場合は、直前に手動で「最新を取得」を一通り
実行してから止めるとよい）

## 4. データ移行スクリプトの実行

```
docker build --target builder -t oshi-migrate ./frontend
docker run --rm --env-file .env oshi-migrate node scripts/migrate-postgres-to-firestore.mjs
```

- 6テーブル分の移行件数と、最後にPostgres側`count(*)`とFirestore側の集計クエリを突き合わせた
  結果（`[verify] ... OK` / `MISMATCH`）が出力される。
- 途中で失敗しても、ドキュメントIDは旧テーブルの主キーをそのまま使っているため、
  **再実行すれば上書きになるだけで安全**（重複は発生しない）。
- 最後に `[migrate] done — all counts match` が出るまで、`MISMATCH`が出た場合は原因を
  確認してから先に進むこと（例: 移行中にcronが動いてしまい件数がズレた等）。

## 5. Firestore側のデータを軽く確認する

[Firebaseコンソール](https://console.firebase.google.com/) の Firestore Database から、
以下を目視で確認する。

- `target_accounts` に登録済みアカウントが並んでいること
- `media_assets` の件数がPostgres側と概ね一致していること（手順4の`[verify]`で確認済みのはず）
- `app_users` / `user_subscriptions` に見覚えのあるemailが入っていること

## 6. アプリの切り替え（コード＋設定の入れ替え）

1. `.env` から `PG_*`（`PG_HOST` / `PG_PORT` / `PG_USER` / `PG_PASSWORD` / `PG_DB`）を削除する
   （もう読まれないが、消しておかないとどちらが正なのか将来紛らわしくなる）。
2. 新しいコード（本PRの内容）で再ビルド・再起動する。

   ```
   docker compose up -d --build
   ```

   `docker-compose.yml`は`DATABASE_URL`ではなく`FIREBASE_PROJECT_ID`/`FIREBASE_CLIENT_EMAIL`/
   `FIREBASE_PRIVATE_KEY`を要求するようになっているため、`.env`に設定漏れがあると
   起動時に `FIREBASE_PROJECT_ID を .env に設定してください` のようなエラーで即座に落ちる
   （＝Postgres設定のまま起動してしまう事故は起きない）。

## 7. 動作確認チェックリスト

ログインして以下を一通り確認する（推しリストに最低1アカウントは登録された状態で確認すること）。

- [ ] ログインでき、推しリスト（`/accounts`）に既存の登録アカウントが表示される
- [ ] 画像一覧（`/`）が表示され、無限スクロールでページングされる（キーセットページネーションの確認）
- [ ] 「最新を取得」ボタンを押して新着チェック→反映まで動く（`/api/collect`, `/api/reveal`）
- [ ] 「過去を読み込む」（バックフィル）ボタンが動く（`/api/backfill`）
- [ ] 顔フィルターのチップ切り替え・個別の手動上書きが動く（`/api/media/[mediaKey]` PATCH）
- [ ] アカウントを新規追加できる（`target_accounts`が新規作成され、次回バッチでID解決される）
- [ ] アカウントを削除すると、他に誰も推していない場合のみ画像も一緒に消える
      （カスケード削除の確認。Firebaseコンソールで`media_assets`/`share_links`が
      消えていることも見ておく）
- [ ] 共有リンクを発行・無効化できる（`/api/share`）。発行したリンク（`/s/<token>`）が
      ログイン無しで開ける
- [ ] 使用量ダッシュボード（`/usage`）に日次・月次のグラフと直近の呼び出し履歴が出る
- [ ] cronが次のスケジュールで自動実行され、`api_usage_log`に新しいログが増えている

## 8. ロールバック手順（切り替え後に問題が見つかった場合）

Postgres側のデータは手順3〜4の間は変更していないので、そのまま残っている。

1. `docker compose down` で新環境を止める。
2. 旧コード（本PR適用前のコミット）に戻す（`git checkout <直前のコミット>`）。
3. `.env` に `PG_*` を書き戻す（手順6で消していた場合）。
4. `docker compose up -d --build` で旧環境を再起動する。

ただしロールバック後は、Firestore移行後にアプリ経由で発生した新着データ（新規ツイート取得・
バックアップ・顔検出結果等）はPostgres側に反映されない点に注意
（cronを止めていた期間＋切り替え後の運用期間のデータがFirestoreにしか無い状態になる）。
そのため切り替え後1〜2回のcronサイクルが正常に回ることを確認できるまでは、
Postgresのコンテナ・データをすぐには消さずに残しておくこと。

## 9. 後片付け（切り替えが安定して運用できたら）

- 旧Postgresサーバー上のデータベース・ユーザーは、必要な期間（例: 1〜2週間の様子見後）を置いてから削除する。
- リポジトリ内には既に`db/init/*.sql`は残していない（本PRで削除済み）。旧スキーマを参照したい場合は
  移行前のコミット履歴を参照する。
- `.env`の`PG_*`一式は削除済みであることを確認する（手順6で実施済みのはず）。
