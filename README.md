<p align="center">
  <img src="app/src/main/res/drawable-xxhdpi/logo_full.png" alt="Seerr" width="320">
</p>

<h3 align="center">Seerr for Android</h3>

<p align="center">
  An unofficial Android app for <a href="https://github.com/seerr-team/seerr">Seerr</a>, the media request manager for Plex, Jellyfin and Emby.
</p>

---

## What it is

The app loads **your own Seerr server's interface**, so it looks and works exactly like Seerr in a browser, with every feature included, and it always matches whatever Seerr version you run. On top of that, it adds the things a browser tab can't do well on a phone:

- **Instant new-request alerts with Approve / Decline buttons.** A notification arrives within seconds of someone making a request, with the poster and buttons to approve or decline it right from the notification. This uses Seerr's built-in ntfy notifications: a Seerr admin can switch it on with one tap in the app's settings, and the app then keeps a lightweight connection to the topic. No extra app, Firebase or polling is needed.
- **Request status notifications.** You get alerts when your own requests are approved, declined or become available. The app checks your server about every 15 minutes for these. Seerr's "Web Push" option needs browser features that Android apps can't use.
- **Plex sign-in that works.** Plex sign-in opens in your browser, so Google and Apple sign-in and saved passwords work. The app closes the browser tab when you're done.
- **Back button support.** Back closes an open Seerr modal first, then goes back a page. Pressing it twice at the start exits the app.
- **Pull down to refresh.** This is ignored while a modal is open or a panel is scrolled.
- **Long-press shortcuts** for Discover, Requests, Profile and App settings, matching Seerr's web app.
- **Share to Seerr.** Share a TMDB link or a title from any app to open it or search for it in Seerr.
- **Self-hosting support.** Plain `http://` LAN addresses work, as do certificates from your own CA (once installed in Android settings) and reverse proxies that ask for a username and password (basic auth) or redirect to a login page like Authelia or Authentik.
- **Edge-to-edge dark UI** that blends into Seerr's own colours, with fullscreen video and file upload support.
- **Built-in update check.** The app checks GitHub releases once a day and offers the new APK.

Requires Android 8.0 or newer.

## Install

1. Download the latest `Seerr-x.y.z.apk` from [Releases](../../releases/latest).
2. Open it on your phone and allow installing from your browser or file manager when Android asks.
3. Enter your server address, for example `https://requests.example.com` or `192.168.1.10:5055`, and sign in as usual.

App settings, such as notifications, Plex sign-in mode, pull to refresh, changing server and signing out, are under the **App settings** long-press shortcut on the app icon. They also appear on the error screen if the server can't be reached.

## Instant alerts

In the app, long-press the icon, tap **App settings**, and turn on **Instant new-request alerts**:

- **If you're a Seerr admin**, the app switches on Seerr's ntfy agent for you. It uses a private, randomly named topic on the free [ntfy.sh](https://ntfy.sh) service, or your own ntfy server if you prefer. If Seerr already sends to ntfy, the app listens to that same topic, so every phone you set up shares it.
- **If you're not an admin**, enter the ntfy server and topic your admin gave you.

Then allow background activity when asked, so alerts still arrive instantly while the phone is asleep. Android shows a small silent "Instant alerts" notification while the connection is open. You can hide it by long-pressing it and turning off the **Background connection** category.

Approve and Decline appear on the alert when you're signed in to Seerr in the app as someone who can manage requests.

## Building

GitHub Actions builds the APK on every push and publishes a release when you push a version tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```

To build locally, open the folder in Android Studio, or run `./gradlew assembleDebug`.

### Release signing

Android only installs an update over an existing install if both are signed with the same key. Add these repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | key alias |
| `SIGNING_KEY_PASSWORD` | key password |

Without them, builds still work but are signed with a throwaway key.

## Credits

All of the actual app is [Seerr](https://github.com/seerr-team/seerr) by the Seerr team (MIT). The icons and logo come from that project. This wrapper isn't affiliated with or endorsed by the Seerr team.

## License

MIT
