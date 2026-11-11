# Headless mode gate

Headless JUnit only. No Minecraft client is launched. A row marked **needs a live run** was not executed and is not a pass.

The design-doc estimate for true no-render is about 800–1100 MB private. Unit tests prove the policy flags. They do not measure private bytes. Do not read a unit-test pass as that memory number. The Phase 0 visible 1.75 baseline remains peak private about 2341 MB.

| Check | Result |
| --- | --- |
| `normal` does not hide, show, or grab without `windowPos` | `HiddenWindowPolicyTest.normalWithoutWindowPosIsUnchanged` |
| `normal` with `windowPos` still hides, places, and shows without activate | `HiddenWindowPolicyTest.normalWithWindowPosStillHidesPlacesAndShowsWithoutActivate` |
| `hidden` stays hidden, and presenting still shows it | `HiddenWindowPolicyTest.hiddenStaysHiddenAndDoesNotGrabTheCursor`, `HiddenWindowPolicyTest.presentingShowsWithoutChangingTheHideRuleForANormalClient` |
| Hidden 1 fps reduction is unchanged, and headless is not that reducer | `HiddenRenderPolicyTest.normalAndHeadlessDoNotReduce`, `HiddenRenderPolicyTest.hiddenReducesAndPresentingDoesNot` |
| Headless flags are true only for `headless` | `HeadlessPolicyTest.headlessInitPathIsTakenOnlyWhenTheModeIsHeadless` |
| The property and `#set runMode` both select headless, and `hidden` does not take that path | `HeadlessPolicyTest.propertyAndSettingBothSelectHeadlessAndLeaveTheOtherModesAlone` |
| `requestPresent` is ignored for `normal` and `headless`, and still works for `hidden` | `HeadlessPolicyTest.presentIsIgnoredUnlessTheModeCanRenderSwap` |
| `canRenderSwap` is hidden only; `canStreamView` is headless only | `HeadlessPolicyTest.presentIsIgnoredUnlessTheModeCanRenderSwap` |
| A pathing goal, a pathing command, and tab-complete still construct while the property is `headless` | `HeadlessPolicyTest.pathingGoalStillConstructsWhileHeadless` |
| Headless stays unshown, including `windowPos` and a present | `HiddenWindowPolicyTest.headlessStaysHiddenAndDoesNotShowForWindowPosOrPresent` |
| No stream, socket, or POV switcher | `ViewCapability` has no connect method. Not a live check. |
| No GPU device and no SDL window when the process property is `headless` | `HeadlessPolicy.keepsRealGpuDevice()` is false, `skipSdl()` is true, `skipAtlasUpload` / `skipGameRenderer` / `skipRenderThread` are true. `startsRenderThread` is false. Hidden and normal stay the other way: `HeadlessPolicyTest.hiddenAndNormalStillKeepTheDeviceAndTheRenderThread`. The mixins call these flags. JUnit does not apply the mixins. |
| `#set normal` after a headless start still skips GPU work | `HeadlessPolicyTest.propertyAndSettingBothSelectHeadlessAndLeaveTheOtherModesAlone` asserts `skipGpuWorkNow()` |
| Private bytes in the 0.8–1.1 GB estimate, versus visible 1.75 ~2341 MB | **needs a live run** |
| Tick health, path quality versus visible 1.75, stability | **needs a live run** |
| A real server join with pathing and TenorClef still running | **needs a live run** |
| `SoundEngine.reload` cancel does not crash a live sound tick | **needs a live run** |
| Font reload skip does not throw when a tick measures text | **needs a live run** |
| Hidden mode still hides, keeps the GL context, and can show at 2600,40 without focus | **needs a live run** |

Mixin methods stay small and call these policies. JUnit does not apply them. `compileLaunchJava` is the compile check.
