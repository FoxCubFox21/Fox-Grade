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
