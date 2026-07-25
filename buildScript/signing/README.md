# DragonVPN public test signing key

`dragonvpn-test.jks.b64` contains a **public development-only** signing key.
It exists solely so GitHub Actions test APKs can update one another.

It is not a release key and provides no publisher identity: anyone with this
repository can sign an APK with it. Before any public release, replace this
workflow with a private keystore stored in GitHub Actions secrets and change
the application id from the `.test` build package if appropriate.
