# Web/Android Firestore統一 + Web版Google認証化 設計書

## 実装状況(2026-07-06時点)

**Web側・Android側ともに実装完了。** 残るのはカットオーバー手順(下記「実施順序」節)の実施のみ。

完了:
- Web: Firebase Auth移行一式(`auth.config.ts`/`auth.ts`/`lib/firebaseAdmin.ts`(新規)/`lib/auth/verifyFirebaseIdToken.ts`(新規)/`lib/firebaseClient.ts`(新規)/`types/next-auth.d.ts`(新規)/`app/(app)/login/page.tsx`+`LoginButton.tsx`(新規)/`package.json`に`firebase`追加)
- Web: repo層のuidスコープ化(`lib/repo/targetAccounts.ts`, `media.ts`, `shareLinks.ts`, `apiUsage.ts`)。`ml_tags`/`embedding`削除。`subscriptions.ts`/`appUsers.ts`/`userAccounts.ts`削除(削除済みファイルなのでgit復元不要、単純に無くなっている)。
- Web: 全Route Handler(`accounts`, `backfill`, `collect`, `media`, `media/[mediaKey]`, `reveal`, `share`, `usage`, `s/[token]/media`)をuid対応に更新。
- Web: `worker/batch.js`, `backup.js`, `faceDetect.js`, `xapi.js`に`OWNER_UID`(env)を通した。
- Web: `firestore.indexes.json`のcollectionGroup名リネーム(`targetAccounts`/`mediaAssets`/`apiUsageLog`)、`user_subscriptions`インデックス削除。
- Web: `frontend/scripts/migrate-to-user-tree.mjs`(新規、Firestore→Firestore移行スクリプト)作成済み。
- Web: `.env.example`/`docker-compose.yml`のOIDC_*削除、`NEXT_PUBLIC_FIREBASE_*`/`OWNER_UID`追加。
- Android: `FirestoreMirror.kt`のsnake_case+Timestamp+merge化。`backed_up`/`backup_attempts`/`face_reviewed`/`revealed`(常にtrue固定)/`backfill_cursor`/`backfill_done`を読み書き対応。`logApiUsage()`追加。
- Android: バックフィル機能。`XApiClient.kt`に`untilId`/`onPage`パラメータ追加、`FetchPhotoMediaResult.oldestId`追加。`TargetAccountEntity.kt`に`backfillCursor`/`backfillDone`追加、`AppDatabase`をv2→v3(`MIGRATION_2_3`でカラム追加)。`MediaRepository.backfillAll()`新設。`AccountsScreen`に「過去の投稿を読み込む」ボタン追加(`backfillDone`のアカウントのみ表示・完了時は一覧が非表示化)。
- Android: 同期アイコン。`TargetAccountDao.upsert/upsertAll`、`MediaAssetDao.updateCloudFields/getExistingMediaKeys/getOldestTweetId`追加。`MediaRepository.restoreFromCloud()`を既存行の上書きにも対応するよう拡張(オンボーディング初回復元・設定画面「クラウドから復元」・トップバー同期アイコンの3箇所から共通で呼ばれる一本化実装)。`MediaListScreen`のTopAppBarに`Icons.Filled.Sync`アイコン追加。
- Android: API使用量ログ。`FirestoreMirror.logApiUsage()`(Web版と同じ単価定数)。`MediaRepository.refreshAll()`/`backfillAll()`の`resolveUserId`/`fetchPhotoMedia`呼び出し後にそれぞれ記録。
- Android: `./gradlew clean compileDebugKotlin`でBUILD SUCCESSFULを確認済み(警告のみ、`SecureSettings.kt`のEncryptedSharedPreferences非推奨警告は既存分)。

**未検証**: この環境にNode.js/npmが無くWeb側の型チェック・ビルドを実行できていない。次回、Docker(`docker build --target builder ./frontend`等)かNode環境で`next build`相当の型チェックを一度通すこと。特に`next-auth/providers/credentials`のインポート・`auth.ts`のCredentialsプロバイダの型、`firebase`パッケージの新規追加分。

次回はカットオーバー手順(下記「実施順序」節)に着手する。特に手順1(Android版で一度サインインしuid確認)は新スキーマ対応済みのAndroid版で実行してよい。


## Context

現状、Web版(Next.js)とAndroid版は同じ「oshi-x-image-collector」だが、Firestore上で**別々のデータツリー**を持っている。

- Web版: `target_accounts`/`media_assets`等がトップレベルコレクションで、複数ユーザーが同じ推しアカウントを共有購読できるモデル(Pocket ID OIDC認証、`user_subscriptions`で紐付け)。これはクロール・X API課金を1回で済ませるための設計だった。
- Android版: 既にGoogle Sign-In + `users/{uid}/targetAccounts`, `users/{uid}/mediaAssets`という、ユーザーごとに独立したツリーへのFirestoreミラーバックアップを実装済み。

ユーザーへのヒアリングで、**Web版も実質本人しか使っていない**(共有リンク機能は「他人に見せる」ためであり「他人と共有購読する」用途ではない)ことが確認できた。したがって元々の「複数ユーザーで共有購読しクロールコストを節約する」設計は不要な複雑さであり、Android版が既に持っている`users/{uid}`単位のモデルに統一し、Web版もGoogle認証(Firebase Auth)でログインすることで、**同じGoogleアカウントなら同じuid = 同じデータをWeb/Androidどちらからも見られる**ようにする。

これにより:
- Web版で収集した過去データをAndroid版からも閲覧・復元できる
- Android版で収集したデータもWeb版から閲覧できる
- 認証基盤がFirebase Authに一本化される

**調査の過程で判明した追加スコープ**: 元々の依頼は「Web版をAndroidに移植する」ことだったが、Web版が持つ「バックフィル」機能(推しアカウント登録時に、新着だけでなく過去に遡って投稿を掘り尽くす機能。`worker/batch.js`の`backfillAllAccounts`、`target_accounts.backfill_cursor`/`backfill_done`で進捗管理)がAndroid版には移植されておらず、新着方向の同期(`lastFetchedId`を起点にした`since_id`取得)しかない。初回同期でたまたま直近300件が取れるだけで、それより古い投稿は永久に取得できない。今回のFirestore統一に合わせて、**Android版にもWeb版と同じ本来の意味でのバックフィルを実装する**(Web版を削る方向ではなく、Androidを追いつかせる方向で寄せる)。

**重要な前提(簡素化の根拠)**: Android版アプリはまだ誰にもリリースされておらず、実運用データが存在しない。したがって「既存のAndroid形式データとの後方互換性」を一切気にする必要がなく、`FirestoreMirror.kt`は最初から新スキーマ(snake_case + Timestamp + merge書き込み + バックフィル対応)でそのまま書けばよい。また、Android版の既存Google Sign-In機能を使って**先に自分のuidを確定させてからWeb版をデプロイできる**ため、Web側に段階的なブートストラップ処理を作る必要がない(詳細は各節参照)。

## 方針・設計判断

- **コレクション名はAndroid側の`users/{uid}/targetAccounts`, `users/{uid}/mediaAssets`をそのまま採用**(Android側`firestore.rules`は変更不要)。ドキュメント内の**フィールド名はWeb側のsnake_case規約に統一**(Web側のクエリ・複合インデックスが依存しているため、Android側のミラー実装を合わせる方が変更範囲が小さい)。
- **書き込みは常にmerge**(Web=Admin SDKの部分update、Android=`SetOptions.merge()`)。今回の変更で双方の書き込みフィールド集合はほぼ一致するが、念のため相手が管理する値を不用意に消さないための安全策として徹底する。**Web/Android間で同じフィールドに対する競合が起きた場合は「後から書き込んだ方が勝つ」で良い**(ユーザー判断: 個人利用の範囲なら調停ロジックは不要)。
- **`ml_tags`/`embedding`は削除する**: Postgres時代からの将来用(ML分類・CLIPベクトル検索)フィールドだが、確認したところ実際には常に`null`で、フロントエンドのUIからも一切参照されていない(`app/api/media/route.ts`がレスポンスに`ml_tags`を含めているだけ)。使っていない・紛らわしいとのユーザー判断により、Web側からもこの機会に完全に削除する(Android側に合わせる方向)。将来ML機能を追加する際に改めて設計する。
- **`backfill_cursor`/`backfill_done`は例外的にWeb/Android共有フィールドとする**: 後述の通りAndroid版にもバックフィル機能を実装するため、この2フィールドは「どちらのプラットフォームがバックフィルを実行しても、常に前回までの進捗の続きから遡る」という共有の進捗マーカーとして扱う(Web側の`updateBackfill`も元々トランザクションなしの単純updateであり、今回もそれに合わせる。同時に両プラットフォームでバックフィルを走らせると多少の手戻り・二重取得の可能性はあるが、個人利用規模では許容範囲とする)。
- **日時型の統一(重要な見落とし)**: Android版は現在`postedAt`等を生のLong(epochミリ秒)でミラーしているが、Web側はFirestore `Timestamp`型で`orderBy`/範囲クエリを行っている。同じフィールド名でLongとTimestampが混在すると並び替え・範囲クエリが壊れるため、**Android側の書き込みをTimestamp型に変換する**必要がある。
- `share_links`は**トップレベルのまま**(公開の共有リンク解決はuidを知らないtokenだけのアクセスのため)。`created_by`(メール)を`owner_uid`に置き換え、公開ルートがtoken→owner_uid→`users/{uid}/...`の順で解決する。
- `app_users`/`user_subscriptions`は退役(コード削除、データは移行対象外)。
- **`api_usage_log`も`users/{uid}/apiUsageLog`に統一する**: Web版だけでなくAndroid版も(本人が個人契約している)X APIの利用料が発生するため、「同じGoogleアカウントの本人の使用料」として合算表示できるようにする。トップレベルのままだとAndroid版のクライアントSDKから書き込む際に`firestore.rules`のカバー範囲外(`users/{uid}/**`のみ許可)になってしまうため、`users/{uid}/`配下に置く。

## Web側の変更

### 認証(NextAuth v5 → Firebase Auth Google Sign-In)

- `frontend/lib/firestore.ts`: Admin SDK初期化ガードを`frontend/lib/firebaseAdmin.ts`(新規)に抽出し、Firestore/Admin Authで共用。
- `frontend/lib/auth/verifyFirebaseIdToken.ts`(新規、Node専用): `firebase-admin/auth`の`verifyIdToken`をラップ。**Edge実行の`middleware.ts`が読み込む`auth.config.ts`には絶対にimportしない**(Edgeバンドラがfirebase-adminを巻き込んで壊れるため)。
- `frontend/auth.config.ts`: `pocketid` OIDCプロバイダを削除。`providers: []`にする(middlewareのセッションJWT検証だけならprovider不要)。
- `frontend/auth.ts`:
  - `Credentials`プロバイダ(id: `firebase`)を追加。`authorize({idToken})`で`verifyFirebaseIdToken`を呼び、`{id: uid, email, name}`を返す。
  - `signIn`コールバックを`upsertAppUser`呼び出しから**allow-listゲート**に変更: `return user.id === process.env.OWNER_UID`のみ(Google Sign-Inは任意のGoogleアカウントを受け付けてしまうため、これが唯一のアクセス制御になる)。`OWNER_UID`は後述の通りAndroid版アプリで先に取得済みの値をデプロイ時から設定するため、ブートストラップ用の別経路は不要。
  - `jwt`/`session`コールバックで`session.user.uid`を伝播(現状は`.email`のみ伝播)。
- `frontend/types/next-auth.d.ts`(新規): `Session.user.uid`/`JWT.uid`の型拡張。
- `frontend/lib/firebaseClient.ts`(新規): クライアント側Firebase SDK初期化(`NEXT_PUBLIC_FIREBASE_*`)、`signInWithGooglePopup()`。
- `frontend/app/(app)/login/page.tsx`: クライアントコンポーネント化。Googleサインインボタン→Firebase IDトークン取得→`signIn('firebase', {idToken})`(next-auth/reactの`signIn`を使うことでCSRFトークンは自動的に扱われる。生fetchに書き換えないこと)。
- `frontend/package.json`: `firebase`(クライアントSDK)を追加。

### データアクセス層(uidスコープ化)

- `frontend/lib/repo/targetAccounts.ts`, `frontend/lib/repo/media.ts`: `col()`を`col(uid) => db.collection('users').doc(uid).collection('targetAccounts'|'mediaAssets')`に変更し、全exported関数の先頭に`uid`引数を追加。
- `frontend/lib/repo/media.ts`: `ml_tags`/`embedding`を`MediaRow`型・`fromDoc`・`insertMediaBatch`から削除(未使用フィールドの撤去)。`frontend/app/api/media/route.ts`のレスポンス組み立てからも`ml_tags: m.ml_tags`の行を削除。移行スクリプトも新ツリーへコピーする際にこの2フィールドを引き継がない。
- `frontend/lib/repo/subscriptions.ts`, `frontend/lib/repo/appUsers.ts`: **削除**。
- `frontend/lib/repo/userAccounts.ts`: **削除**し、`targetAccounts.ts`に`listAll(uid)`(旧`getSubscribedAccounts`相当、joinなしの直接リスト)を追加。
- `frontend/lib/repo/shareLinks.ts`: `created_by`(email)を`owner_uid`に置き換え。`create(token, screenName, ownerUid)`。
- 各Route Handler(`app/api/accounts`, `backfill`, `collect`, `media`, `media/[mediaKey]`, `reveal`, `share`)で`session.user.email`→`session.user.uid`に変更し、repo呼び出しに`uid`を渡す。`accounts/route.ts`のPOST/DELETEは「共有購読」ロジック(`subscriptions.addSubscription`/購読者数チェック)を削除し、`createIfNotExists(uid, screenName)`/`deleteCascade(uid, screenName)`を直接呼ぶだけにする。
- `frontend/app/api/s/[token]/media/route.ts`(公開ルート、認証なしのまま): `getByToken`後、`link.owner_uid`を使って`users/{uid}/...`を解決する。
- `frontend/lib/repo/apiUsage.ts`: `col()`を`col(uid) => db.collection('users').doc(uid).collection('apiUsageLog')`に変更し、`logUsage(uid, entry)`/`getUsageStats(uid)`とする(集計ロジック自体は変更不要)。
- `frontend/app/api/usage/route.ts`: 現状`auth()`を呼んでいない(全ルート共通のmiddlewareチェックのみに依存)実装だったが、per-uid化に伴い`auth()`で`session.user.uid`を取得し`getUsageStats(uid)`に渡すよう変更。
- `frontend/worker/batch.js`, `backup.js`, `faceDetect.js`, `xapi.js`: リクエストコンテキスト外で動くため、`process.env.OWNER_UID`を各repo呼び出し(`targetAccounts.*`/`media.*`/`apiUsage.logUsage`)に渡す。`state.js`は変更不要。
- `firestore.indexes.json`: `collectionGroup`を`target_accounts`→`targetAccounts`, `media_assets`→`mediaAssets`にリネーム(サブコレクションでも同名なら適用される)。`user_subscriptions`のインデックスは削除。

### 移行スクリプト・環境変数

- `frontend/scripts/migrate-to-user-tree.mjs`(新規): 既存の削除済みスクリプト(`git show a100c86:frontend/scripts/migrate-postgres-to-firestore.mjs`で復元可能)と同じ「500件ずつバッチset、冪等、件数検証」パターンで、Firestore→Firestore移行を行う。
  - トップレベル`target_accounts`→`users/{OWNER_UID}/targetAccounts`、`media_assets`→`users/{OWNER_UID}/mediaAssets`(ドキュメントIDそのまま、フィールド名も変更不要=既にsnake_case)。
  - トップレベル`api_usage_log`→`users/{OWNER_UID}/apiUsageLog`(ドキュメントIDは自動採番だったため、新規`.add()`で良い)。
  - `share_links`は移動せず、`owner_uid: OWNER_UID`を一括追加。
  - `app_users`/`user_subscriptions`は対象外。
  - 旧トップレベルコレクションは**削除しない**(Postgres decommissionと同様、1〜2週間の観察期間を置いてから手動削除)。
- `.env.example`/`docker-compose.yml`: `OIDC_ISSUER`/`OIDC_CLIENT_ID`/`OIDC_CLIENT_SECRET`を削除。`NEXT_PUBLIC_FIREBASE_API_KEY`/`NEXT_PUBLIC_FIREBASE_PROJECT_ID`/`NEXT_PUBLIC_FIREBASE_APP_ID`(Android版と同じFirebaseプロジェクトの値)、`OWNER_UID`(下記手順で事前に取得した値)を追加。

## Android側の変更

### FirestoreMirrorのスキーマ統一

`android-app/app/src/main/java/com/hilamalu/oshixcollector/data/backup/FirestoreMirror.kt`を変更(`GoogleAuthManager`/`FirebaseAppProvider`/`firestore.rules`/`CloudBackupSettings`は変更不要、既に正しく動作している)。

- フィールド名をWeb側と同じsnake_caseに変更(`xUserId`→`x_user_id`等)。
- `postedAt`/`createdAt`/`lastCheckedAt`のLong(ミリ秒)を書き込み時に`com.google.firebase.Timestamp`に変換(読み込み時は`.toDate().time`で戻す)。
- `.set(map)`(全上書き)を`.set(map, SetOptions.merge())`に変更。
- Androidが新たに書き込むフィールドを追加: `backed_up`(`r2BackupUrl != null`から算出)、`backup_attempts`、**`face_reviewed`**(既存の未ミラーバグの修正——現状復元のたびに`false`にリセットされてしまう)、`revealed`(常に`true`固定、Android側にゲーティングUIはないため)、**`backfill_cursor`/`backfill_done`**(後述のバックフィル機能実装に伴い、Web側と共有で読み書きする)。
- `ml_tags`/`embedding`はWeb側から削除するため、Android側もそもそも扱わない。

### バックフィル機能の追加(Web版からの機能移植)

現状、`XApiClient.fetchPhotoMedia`は`since_id`(新着方向)のみ対応しており、`TargetAccountEntity`にも`backfill_cursor`/`backfill_done`に相当するフィールドが存在しない。Web版の`worker/batch.js`の`backfillAllAccounts`/`frontend/worker/xapi.js`の`until_id`パラメータ・`oldestId`/`exhausted`の扱いを移植する。

- **`XApiClient.kt`**: `fetchPhotoMedia`に`untilId: String? = null`パラメータを追加し、リクエストパラメータに`until_id`を含める(Web同様、`since_id`と`until_id`は排他的に使う想定)。`FetchPhotoMediaResult`に`oldestId: String?`を追加(現状`newestId`/`exhausted`のみで`oldestId`が無い)。ページごとの進捗コールバック(`onPage`)も任意で追加(Web版の`onPage`相当、進捗表示用)。
- **`TargetAccountEntity.kt`**: `backfillCursor: String? = null`, `backfillDone: Boolean = false`を追加。`AppDatabase.kt`(現在`version = 2`)を`version = 3`にバンプし、`ALTER TABLE`でカラム追加するRoom Migrationを追加。
- **`MediaRepository.kt`**: `backfillAll(maxPagesPerAccount: Int = 5)`を新設(`refreshAll`と対になる関数)。各アカウントについて`backfillDone == true`ならスキップ、`untilId = account.backfillCursor`(空ならローカルDB内のそのアカウントの最古`tweetId`)を起点に`fetchPhotoMedia(untilId = ...)`を呼び、取得した過去メディアを`revealed`相当の扱いで即保存(Android元々ゲーティングなし)、`account.copy(backfillCursor = result.oldestId ?: untilId, backfillDone = result.exhausted)`で更新。バックアップON時は`firestoreMirror.mirrorTargetAccount`/`mirrorMediaAssets`で同期(`backfill_cursor`/`backfill_done`もここで書き込まれる)。
- **UI**: Web版の「過去を読み込む」ボタンに相当する手動トリガーを追加(Android版はcron/WorkManagerが元々無く全て手動実行のため、既存の「最新を取得」ボタンと同様の一貫したUXになる)。`AccountsScreen`/`AccountsViewModel`あたりに「過去の投稿を読み込む」ボタンを追加し、`repository.backfillAll()`を呼ぶ。`backfillDone`のアカウントはボタンをグレーアウト/非表示にする。

### クラウド同期の見直し(トップバーに同期アイコンを追加)

Web/Android間の競合は「後勝ち」で許容する方針のため、Web側の編集(例: `is_face`の手動修正)をAndroid側に取り込むには、Android側から能動的に最新のクラウド状態を取得する導線が要る。**ローカルの変更発生時に自動でクラウドへpushする(push)のは既存の`firestoreMirror.mirror*`呼び出しで既に実装済み**。今回追加するのは**pull側**(クラウドの最新状態をローカルへ取り込む導線)。

- 既存の`MediaRepository.restoreFromCloud()`(現在は「ローカルに無い行だけ追加」の初回復元専用)を、既存行も上書き対象に含む形に拡張する:
  - `TargetAccountDao`に`@Insert(onConflict = OnConflictStrategy.REPLACE)`のupsertメソッドを追加(全フィールドがクラウド由来で、ローカル専有フィールドが無いため単純上書きで問題ない)。
  - `MediaAssetDao`には、`localImagePath`/`backupAttempts`(ローカル専有・端末ごとに異なる値)を保持したまま、クラウド由来フィールド(`r2BackupUrl`, `isFace`, `faceConfidence`, `faceReviewed`)だけを上書き更新する専用メソッドを追加する(既存の`updateFaceResult`/`updateR2BackupUrl`と同様の、対象を絞ったUPDATE文のスタイルに合わせる)。
- UI: 直近リリースされたTopAppBar構成(既存コミットでカード分割・TopAppBar化済み)のメイン画面右上に同期アイコン(`Icons.Default.Sync`等)を追加。タップで上記の拡張済み同期処理を呼び出し、実行中はアイコンをスピナーに差し替える。クラウドバックアップ未設定/未サインインの場合は既存のSettings/Onboardingと同様にサインインを促す。
- これにより、オンボーディング時の初回復元も、この右上アイコンによる随時同期も同じ関数(拡張後の`restoreFromCloud`)を使う一本化した実装にする(専用の別関数は用意しない)。

### API使用量ログの追加(Web版との合算)

現状Android版は`XApiClient`でXのAPIを呼んでも何も記録しておらず、使用量・コストを確認する画面も無い。同じGoogleアカウント(同一人物)の使用料としてWeb版と合算表示する。

- `FirestoreMirror.kt`に`logApiUsage(purpose, endpoint, screenName, resource, quantity)`を追加(Web版`api_usage_log`と同じ形: `called_at, purpose, endpoint, screen_name, resource, quantity, unit_cost_usd, cost_usd`を`users/{uid}/apiUsageLog`に`.add()`)。単価定数(`unit_cost_usd`)はWeb版の`docker-compose.yml`(`UNIT_COST_USD_USER_READ`/`UNIT_COST_USD_POSTS_READ`)と同じ値をKotlin側にも定数として持たせる。未サインインの場合は既存の`userDocOrNull()`と同様に静かにスキップする(バックアップ機能全体と同じ「未サインインなら何もしない」挙動を踏襲、この機能のためだけにサインインを強制しない)。
- `MediaRepository.refreshAll()`/`backfillAll()`から、`client.resolveUserId(...)`実行後に`purpose: "resolve"/resource: "user_read"`、`client.fetchPhotoMedia(...)`実行後に`purpose: "collect"or"backfill"/resource: "posts_read"`で`logApiUsage`を呼ぶ(Web版`worker/xapi.js`の分類に合わせる)。
- Web版の`/api/usage`画面はコード変更不要(`users/{uid}/apiUsageLog`を見るだけで、Android由来の呼び出しも`purpose`別内訳に自然に混ざる)。

## 実施順序(カットオーバー手順)

Android版が未リリース(実データなし)なため、Web版のように「先にコードをデプロイしてから仮ログインでuidを確定する」という段階を踏む必要がない。先にuidを確定させてから、Web版を一発で正しい設定のままデプロイできる。

1. Android版アプリ(ビルド済みのGoogle Sign-In機能はそのまま)で一度サインインし、Firebase Console→Authentication→ユーザー一覧で自分のuidを確認する。
2. Firebase ConsoleでAndroid版と同じFirebaseプロジェクトにWebアプリを登録し、`apiKey`/`appId`を取得。
3. Web側のコード変更一式(認証・repo層・share_links等)をデプロイ。`.env`に`NEXT_PUBLIC_FIREBASE_*`と、手順1で確認済みの`OWNER_UID`を最初から設定する。
4. cronを止めた状態(`RUN_ON_START=false`)で`migrate-to-user-tree.mjs`を実行、件数検証。
5. Android版アプリを新スキーマ対応(`FirestoreMirror.kt`更新・バックフィル機能追加)のビルドでリリースする(旧データとの互換性は考慮不要)。
6. cron再開、動作確認: 片方のプラットフォームでアカウント追加→もう片方から見える/復元できることを確認。共有リンクが引き続き解決できることを確認。
7. 1〜2週間の観察期間後、旧トップレベルコレクション(`target_accounts`/`media_assets`/`app_users`/`user_subscriptions`)を手動削除。

## 検証方法

- Web版: `/login`でGoogleサインインし、既存の推しアカウント一覧・画像一覧が表示されることを確認。
- 移行スクリプト実行後、Firebase Consoleで`users/{OWNER_UID}/targetAccounts`, `mediaAssets`にデータが入っていることを目視確認。
- Android版で「最新を取得」「過去の投稿を読み込む」を実行後、Web版の`/api/usage`にAndroid由来の呼び出し(`purpose: collect`/`backfill`/`resolve`)が合算されて表示されることを確認。
- Android版: 設定画面から「クラウドから復元」を実行し、Web版で収集したアカウント・画像が反映されることを確認。
- Android版で新規に画像を1件同期させ、Web版の画像一覧に表示されることを確認。
- Web版で`is_face`を手動修正(`/api/media/[mediaKey]` PATCH)した後、Android版で右上の同期アイコンをタップし、既存のローカル行(ダウンロード済み画像はそのまま)に対して`is_face`/`face_reviewed`だけが更新され、`localImagePath`が消えない(再ダウンロードが走らない)ことを確認。
- 共有リンク(`/s/[token]`)が未ログイン状態で引き続き閲覧できることを確認。
- Android版で新規追加したアカウントに対して「過去の投稿を読み込む」ボタンを実行し、過去の投稿が取得され、`backfill_done`になるまで繰り返し実行できることを確認。
