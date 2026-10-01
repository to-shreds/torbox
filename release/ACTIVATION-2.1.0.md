# TorBox Drop web activation

Activated 2026-10-01 after Jon explicitly confirmed the Render workspace.

- Page: https://to-shreds.github.io/Misc/TorboxDrop/
- Standalone file: https://to-shreds.github.io/Misc/TorboxDrop/TorBox-Drop.html
- Relay: https://torbox-drop-api.onrender.com/api/
- Render workspace: My Workspace / tea-dakujgmk1f9s73d2v8ng.
- Service: srv-davdjv0u01pc73ec1phg, free plan, Virginia, Node 22, no database or media proxy.
- Successful initial deployment: dep-davdjvou01pc73ec1rmg, source 938cad1b00bc999b03dbfd164220ffc4ba508279. Relay production code is unchanged by activation.
- Frontend source: fbae3a6d9b3f9e6ab49e8e83f9c8c3f263b602f7.
- Misc publication: 3ef3c64d66bbad9f2c7468c8a4c71545b2280d71; Pages run 36933652432 succeeded.
- Activated HTML SHA-256: 1b308d632fc680aee964c023fda83376dacce1c84cf3b321e126df899564dacb.

## Verification

Seven live relay checks passed: health 200, GitHub Pages and file-origin preflight 204, missing authentication 401, invalid authentication 403 with TorBox's BAD_TOKEN result, hostile-origin rejection 403, and unknown-route rejection 404. Invalid-key testing used a deliberately fake key. The first harness expected 401 for BAD_TOKEN; TorBox returns 403. The application already handles the error code correctly, so no application change was needed.

All responses carried no-store headers, and error bodies did not echo the test value. Error-log inspection found no error entries. Files/media never pass through the relay.

Both public HTML URLs returned HTTP 200 with bytes matching the activated source. A live browser opened the exact GitHub Pages URL and confirmed enabled API-key input and Unlock my TorBox, with only the login page visible. No real account credentials were entered.

Activated-source CI run 36933596322 passed 30 core/API/relay checks and all 17 Chromium scenarios. Native release CI 36930416657 also passed. The APK, native code and retained signing identity are unchanged.

The API key is retained only for the tab session and the duration of each relay request. No credential is configured on Render, saved by the page, logged by the application, or put in a database. A free Render instance may take a minute to wake up. A successful real-account download and physical-phone acceptance remain user-side live checks.
