# Sprite Puppet Maker

Android app for building rigged 8-direction sprite puppets from photorealistic AI renders.

- **16 parts × 8 directions** (head, neck, torso, pelvis, upper arms, forearms, hands, thighs, calves, feet; S, SE, E, NE, N, NW, W, SW). A built-in ray-marched 3D mannequin fills every slot, so the puppet is never missing a piece.
- **✦ Generate**: renders the character with OpenAI's image API (your own key) on top of a mannequin pose guide for each direction, so each render lines up with the rig. The front view is the identity reference for the other seven. Each view is cut into parts automatically. Single parts can also be rendered as close-ups.
- **Photo → Parts**: load any full-body photo, remove the background, place 21 joint dots (or let the vision model place them) and cut.
- **Rig**: a 3D skeleton drives the 2D parts, so one animation works in all 8 directions with depth sorting. Idle, walk, run, punch, plus custom keyframe clips.
- **Export**: sprite sheets (8 rows × N frames), atlas + `rig.json`, and project files. Saved to `Downloads/SpritePuppetMaker`.

The UI is `app/src/main/assets/index.html` running in a WebView. `MainActivity` provides the native bridge: OpenAI HTTPS calls, settings storage, file saving and the file picker.

Built by GitHub Actions (`.github/workflows/android.yml`). Download the APK from the run's **SpritePuppetMaker-debug** artifact.
