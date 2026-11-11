# Hidden mode gate

Headless JUnit only. No Minecraft client is launched. A row marked **needs a live run** was not executed and is not a pass.

The Phase 0 visible 1.75 baseline is peak private about 2341 MB, steady about 2290 MB (10 Nov 2026, 7:59–8:42 AM MST). Hidden has to beat that in a later live run. This checklist does not claim that.

| Check | Result |
| --- | --- |
| Default and a missing property are `normal` | `RunModeTest.defaultIsNormalAndDoesNotReadAMissingPropertyAsHidden` |
| The property is the mode before settings, and `#set` wins after | `RunModeTest.propertyIsTheModeBeforeSettingsAndTheSettingIsTheModeAfter` |
| `headless` is stored and is not hidden | `RunModeTest.headlessIsStoredAndIsNotHidden` |
| An unknown property stays normal and does not overwrite the setting | `RunModeTest.unknownPropertyStaysNormalAndDoesNotOverwriteTheSetting` |
| `#set runMode` accepts `normal`, `hidden`, and `headless` only | `RunModeTest.setAcceptsNormalHiddenAndHeadlessAndRejectsOtherTokens`, `RunModeTest.nullTokenIsRejectedWithoutAQuotedEmptyString` |
| `normal` with no window position does not hide, show, or grab | `HiddenWindowPolicyTest.normalWithoutWindowPosIsUnchanged` |
| `normal` with `windowPos` still hides, places, and shows without activate | `HiddenWindowPolicyTest.normalWithWindowPosStillHidesPlacesAndShowsWithoutActivate` |
| `hidden` stays hidden and does not grab the cursor | `HiddenWindowPolicyTest.hiddenStaysHiddenAndDoesNotGrabTheCursor` |
| Presenting shows a hidden window and does not change the stored mode | `HiddenWindowPolicyTest.presentingShowsWithoutChangingTheHideRuleForANormalClient`, `HiddenRenderPolicyTest.flagsFollowTheSettingOnlyWhileHidden` |
| Show position is `windowPos` or 2600,40 | `HiddenWindowPolicyTest.showPositionUsesThePropertyOrTheDefaultCorner` |
| `headless` does not use the hidden 1 fps reducer | `HiddenRenderPolicyTest.normalAndHeadlessDoNotReduce` |
| `headless` stays unshown, including `windowPos` and a present | `HiddenWindowPolicyTest.headlessStaysHiddenAndDoesNotShowForWindowPosOrPresent` |
| Display limiter stays at the caller cap when not hidden, and is at most 20 when hidden | `HiddenRenderPolicyTest.displayCapNeverRaisesTheCallerAndDoesNotSleepAtOneHertz` |
| World draw is about 1 fps while hidden, and every frame otherwise | `HiddenRenderPolicyTest.worldDrawIsAboutOnePerSecondWhileHidden` |
| Framebuffer ask is 16; the applied size is 320x240, and only while hidden | `HiddenRenderPolicyTest.framebufferAsksForSixteenAndAppliesTheClientMinimum` |
| Skip mesh, skip texture upload, skip high-res atlas, and silence sound are true only while hidden | `HiddenRenderPolicyTest.hiddenReducesAndPresentingDoesNot`, `HiddenRenderPolicyTest.flagsFollowTheSettingOnlyWhileHidden` |
| Setting is described and categorized | `SettingCatalogTest` |
| SDL window actually stays hidden, GL context stays alive, no focus steal | **needs a live run** |
| Private bytes below the visible 1.75 baseline (~2341 MB peak / ~2.3 GB) | **needs a live run** |
| Path quality, tick health, CPU, stability | **needs a live run** |
| Unhiding restores a window at 2600,40 (or `windowPos`) without focus, and drawing resumes | **needs a live run** |

Mixin methods stay small and call these policies. They are not executed by JUnit. `compileLaunchJava` is the compile check. A wrong mixin target shows up when a client next starts.
