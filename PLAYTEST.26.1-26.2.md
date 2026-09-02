01 adorabuild-structures: PASSED — loads, structures locate/generate
02 advancement-plaques: FAILED (crash) — VerifyError: toastManager WRITE redirected into read bridge; exposed shim get/put bug + census ignoring verifier verdict (both fixed); mod demoted from clean set
03 almanac: PASSED — tab tooltips correct incl. multi-tab items (colour+recipe+Tuple bridge all live)
04 alternate-current: PASSED — redstone identical to vanilla (its design goal)
05 ambient-environment: FAILED SILENT — loads clean, tint noise never appears; refmap unreadable + 26.2 tint caching suspected. Needs decision/Tier2.
06 amecs: FAILED — nested amecs-mouse-inputs mixin, scroll injection 0 targets on 26.2. Exposed: mixin-check didn't recurse META-INF/jars (fixed) + sig-form selectors uncollected (fixed).
07 appleskin: PASSED — hunger/saturation overlays + tooltips working
08 armor-statues: PASSED — statue GUI opens (setScreenAndShow live-verified), poses persist. Needed deps: puzzleslib+forgeconfigapiport+fabric-api 0.159
09 attributefix: PASSED — armor base 50.0 accepted (vanilla caps at 30)
10 badoptimizations: FAILED (behavioural) — lightmap smoothing lags on light INCREASE (torch placement snaps; vanilla smooth; breaking identical). MixinLightmapExtractor cache invalidation. A/B confirmed by user.
11 better-compatibility-checker: PASSED (shallow — server list renders, no crash; full depth needs a modded server)
12 better-mount-hud: PASSED — hunger+health visible while mounted (100%-rewritten mixin, triple Gui→Hud retarget live-verified)
13 better-stats: PASSED — full custom stats UI (11 rewritten classes) with live data
14 betterhurtcam: PASSED — hurt tilt normal; embedded-selector Gui→Hud retarget live-verified (icon = ukulib avatar, cosmetic)
15 blur-plus: FAILED (crash) — @ModifyVariable blur$beforeRenderScreen1 on GameRenderer scanned 0 targets. NEW TOOL GAP: ModifyVariable selectors invisible to mixin-check (both census jar and six-stage jar affected, both audited 'clean'). Needs checker fix + Tier 2 decision.
16 boat-item-view: PASSED (caveat) — item shows in hand while boating (vanilla never does); hides while actively rowing, believed intended paddle-priority, not A/B'd against 26.1
17 boids: PASSED — loads, config correct, EntityTypes.SALMON/COD/TROPICAL_FISH remaps verified in bytecode+config; conditional RealFishingMixin soft-warns as predicted (squid not in defaults — spread-out is correct)
18 animaticarefabricated: PASSED (init-scope) — Tier 2-authored config classes load and run; full texture depth needs an OptiFine anim pack

## Verified-jar identities (a verdict attaches to these bytes, not to a mod name)
- 0b6b3ea2c6deeb19…  01-adorabuild-structures.jar
- cfe4523b923ce263…  03-almanac.jar
- 42e5ed5b8f165a1c…  04-alternate-current.jar
- 9a3539189f9ab244…  05-ambient-environment.jar
- b9ef34f5406c455b…  06-amecs.jar
- 5bc0de9e579ece1d…  07-appleskin.jar
- 6594d05aee42a6dd…  08-armor-statues.jar
- e13c68149eac7d00…  09-attributefix.jar
- 26cd2528b87b2288…  10-badoptimizations.jar
- fe6718091a43664c…  11-better-compatibility-checker.jar
- 55cd54e664e50059…  12-better-mount-hud.jar
- d10baa89354075c7…  13-better-stats.jar
- 1d1f72817e1f5868…  14-betterhurtcam.jar
- a8e2c8b55749bb31…  15-blur-plus.jar
- 8235c17f9c1dbeaa…  16-boat-item-view.jar
- 8ff71d811c518c74…  17-boids.jar
- acf718ff855bf16b…  18-animaticarefabricated.jar
