#!/usr/bin/env python3
"""Generate the rendering/input bridge tables into fabric-api-bridges.json.
Phase 1: tables derivable from the API diff alone. Phase 2 (needs compiled shims): GuiGraphics →
GuiCompat and RenderSystem → RenderSystemCompat call redirects, keyed on the 1.21.1 descriptors
(with 26.2 class renames applied) and validated against the shim's real signatures via javap."""
import json, re, sys, gzip, subprocess, os, pathlib
MOD = pathlib.Path.home() / "foxgrade-work/foxgrade-mod"
BR = MOD / "src/main/resources/foxgrade/fabric-api-bridges.json"
j = json.loads(BR.read_text())
new = json.load(open("/tmp/mc-26.2-classes-sigs.json"))
rules = json.load(gzip.open(MOD / "src/main/resources/foxgrade/rules.json.gz"))["26.2"]["renames"]
REN = {r["fromFqcn"].replace(".", "/"): r["toFqcn"].replace(".", "/") for r in rules}
REN["net/minecraft/client/gui/GuiGraphics"] = "net/minecraft/client/gui/GuiGraphicsExtractor"
# The authoritative 1.21.1 -> 26.2 class map: mojang(1.21.1) -> official -> intermediary (1.21.1
# tiny) -> 26.2 mojang home (Fox-Grade's bridge). Cached; the rules table only holds curated moves.
_cache = pathlib.Path("/tmp/class-ren-121-262.json")
if _cache.exists(): REN.update(json.load(open(_cache)))
else:
    off2moj = {}
    for ln in open(pathlib.Path.home() / "foxgrade-work/mappings-1.21.1-client.txt"):
        if not ln.startswith("    ") and " -> " in ln:
            moj, off = ln.strip().rstrip(":").split(" -> "); off2moj[off.replace(".", "/")] = moj.replace(".", "/")
    off2im = {}
    for ln in open(pathlib.Path.home() / "foxgrade-work/im121/mappings/mappings.tiny"):
        if ln.startswith("c\t"):
            parts = ln.rstrip("\n").split("\t"); off2im[parts[1]] = parts[2]
    bridge = json.load(gzip.open(MOD / "src/main/resources/foxgrade/intermediary-to-mojang.26.2.json.gz"))
    im2new = {k.replace(".", "/"): v.replace(".", "/") for k, v in bridge["classes"].items()}
    derived = {}
    for off, moj in off2moj.items():
        im = off2im.get(off); nw = im2new.get(im) if im else None
        if nw and nw != moj: derived[moj] = nw
    json.dump(derived, open(_cache, "w")); REN.update(derived)
    print("derived class renames:", len(derived), "| ResourceLocation ->", derived.get("net/minecraft/resources/ResourceLocation"))
SHIM_RENAMES = {"foxgrade/shim/BuiltinItemRendererRegistryShim": "net/fabricmc/fabric/api/client/rendering/v1/BuiltinItemRendererRegistry", "foxgrade/shim/UniformShim": "com/mojang/blaze3d/shaders/Uniform", "foxgrade/shim/RenderCallShim": "com/mojang/blaze3d/pipeline/RenderCall", "foxgrade/shim/RenderTypeCompositeState": "net/minecraft/client/renderer/RenderType$CompositeState", "foxgrade/shim/RenderTypeCompositeStateBuilder": "net/minecraft/client/renderer/RenderType$CompositeState$CompositeStateBuilder", "foxgrade/shim/RenderTypeOutlineProperty": "net/minecraft/client/renderer/RenderType$OutlineProperty", "foxgrade/shim/TesselatorShim": "com/mojang/blaze3d/vertex/Tesselator", "foxgrade/shim/BufferUploaderShim": "com/mojang/blaze3d/vertex/BufferUploader", "foxgrade/shim/VertexFormatModeShim": "com/mojang/blaze3d/vertex/VertexFormat$Mode"}
def shim_desc(d): return re.sub(r"L([\w/$]+);", lambda m: "L" + SHIM_RENAMES.get(m.group(1), m.group(1)) + ";", d)
def ren_desc(d): return re.sub(r"L([\w/$]+);", lambda m: "L" + REN.get(m.group(1), m.group(1)) + ";", d)
PRIM = {"void":"V","boolean":"Z","byte":"B","char":"C","short":"S","int":"I","long":"J","float":"F","double":"D"}
def jdesc(t):
    dims = t.count("[]"); t = t.replace("[]", "")
    d = PRIM.get(t) or "L" + REN.get(t.replace(".", "/"), t.replace(".", "/")) + ";"
    return "[" * dims + d
old = {}; cur = None
for ln in open(pathlib.Path.home() / "foxgrade-work/mappings-1.21.1-client.txt"):
    if not ln.startswith("    ") and " -> " in ln: cur = ln.split(" -> ")[0].strip(); old[cur] = set()
    elif cur and "(" in ln and " -> " in ln:
        m = re.match(r"\s*(?:\d+:\d+:)?(\S+) (\S+)\((.*?)\) -> ", ln)
        if m: ret, name, args = m.groups(); old[cur].add(name + "(" + "".join(jdesc(a) for a in args.split(",") if a) + ")" + jdesc(ret))
def missing_of(oldcls, newcls): return {m for m in old[oldcls] if m not in set(new[newcls]["m"]) and "lambda$" not in m}

KE = "Lnet/minecraft/client/input/KeyEvent;"; ME = "Lnet/minecraft/client/input/MouseButtonEvent;"; CE = "Lnet/minecraft/client/input/CharacterEvent;"; MI = "Lnet/minecraft/client/input/MouseButtonInfo;"
IC = "foxgrade/shim/InputCompat"; GC = "foxgrade/shim/GuiCompat"; GGE = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;"
# ---------- phase 1 ----------
j.setdefault("descWidenings", {})
for owner in ["com/mojang/blaze3d/vertex/PoseStack"]:
    j["descWidenings"].setdefault(owner, {}).update({
        "mulPose(Lorg/joml/Quaternionf;)V": "(Lorg/joml/Quaternionfc;)V", "mulPose(Lorg/joml/Matrix4f;)V": "(Lorg/joml/Matrix4fc;)V",
        "rotateAround(Lorg/joml/Quaternionf;FFF)V": "(Lorg/joml/Quaternionfc;FFF)V"})
for owner in ["com/mojang/blaze3d/vertex/VertexConsumer", "com/mojang/blaze3d/vertex/BufferBuilder"]:
    j["descWidenings"].setdefault(owner, {}).update({
        "addVertex(Lorg/joml/Matrix4f;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;": "(Lorg/joml/Matrix4fc;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
        "addVertex(Lorg/joml/Vector3f;)Lcom/mojang/blaze3d/vertex/VertexConsumer;": "(Lorg/joml/Vector3fc;)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
        "addVertex(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lorg/joml/Vector3f;)Lcom/mojang/blaze3d/vertex/VertexConsumer;": "(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lorg/joml/Vector3fc;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"})
# input call adapters on every GUI owner that exists in 26.2
owners = [c for c in ["net/minecraft/client/gui/screens/Screen", "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen",
    "net/minecraft/client/gui/components/AbstractWidget", "net/minecraft/client/gui/components/EditBox", "net/minecraft/client/gui/components/Button",
    "net/minecraft/client/gui/components/AbstractSelectionList", "net/minecraft/client/gui/components/ObjectSelectionList",
    "net/minecraft/client/gui/components/ContainerObjectSelectionList", "net/minecraft/client/gui/components/AbstractScrollWidget",
    "net/minecraft/client/gui/components/AbstractScrollArea", "net/minecraft/client/gui/components/events/GuiEventListener",
    "net/minecraft/client/gui/components/events/ContainerEventHandler", "net/minecraft/client/gui/components/events/AbstractContainerEventHandler",
    "net/minecraft/client/gui/components/AbstractSliderButton", "net/minecraft/client/gui/components/Checkbox", "net/minecraft/client/gui/components/ImageButton",
    "net/minecraft/client/gui/components/MultiLineEditBox", "net/minecraft/client/gui/components/AbstractButton", "net/minecraft/client/gui/components/AbstractStringWidget",
    "net/minecraft/client/gui/components/tabs/TabNavigationBar", "net/minecraft/client/gui/screens/options/OptionsSubScreen", "net/minecraft/client/gui/components/CycleButton",
    "net/minecraft/client/gui/components/AbstractSelectionList$Entry", "net/minecraft/client/gui/components/ObjectSelectionList$Entry", "net/minecraft/client/gui/components/ContainerObjectSelectionList$Entry"] if c in new]
ADAPT = {
    "keyPressed(III)Z": ("keyPressed", "(" + KE + ")Z", [IC, "keyEvent", "(III)" + KE], []),
    "keyReleased(III)Z": ("keyReleased", "(" + KE + ")Z", [IC, "keyEvent", "(III)" + KE], []),
    "charTyped(CI)Z": ("charTyped", "(" + CE + ")Z", [IC, "charEvent", "(CI)" + CE], []),
    "mouseClicked(DDI)Z": ("mouseClicked", "(" + ME + "Z)Z", [IC, "mouseEvent", "(DDI)" + ME], [0]),
    "mouseReleased(DDI)Z": ("mouseReleased", "(" + ME + ")Z", [IC, "mouseEvent", "(DDI)" + ME], []),
    "mouseDragged(DDIDD)Z": ("mouseDragged", "(" + ME + "DD)Z", [IC, "mouseEvent", "(DDI)" + ME], []),
    "onClick(DD)V": ("onClick", "(" + ME + "Z)V", [IC, "mouseEvent", "(DD)" + ME], [0]),
    "onRelease(DD)V": ("onRelease", "(" + ME + ")V", [IC, "mouseEvent", "(DD)" + ME], []),
    "onDrag(DDDD)V": ("onDrag", "(" + ME + "DD)V", [IC, "mouseEvent", "(DD)" + ME], []),
    "isValidClickButton(I)Z": ("isValidClickButton", "(" + MI + ")Z", [IC, "mouseInfo", "(I)" + MI], []),
}
j.setdefault("callAdapters", {})
for o in owners:
    for k, (nn, nd, pack, extras) in ADAPT.items():
        e = {"newName": nn, "newDesc": nd, "pack": pack}
        if extras: e["extras"] = extras
        j["callAdapters"].setdefault(o, {})[k] = e
XY = [["net/minecraft/client/input/MouseButtonEvent", "x", "()D"], ["net/minecraft/client/input/MouseButtonEvent", "y", "()D"]]
BTN = ["net/minecraft/client/input/MouseButtonEvent", "button", "()I"]
KEYS = [["net/minecraft/client/input/KeyEvent", n, "()I"] for n in ("key", "scancode", "modifiers")]
j["overrideAdapters"] = [
    {"oldName": "keyPressed", "oldDesc": "(III)Z", "newName": "keyPressed", "newDesc": "(" + KE + ")Z", "unpack": KEYS},
    {"oldName": "keyReleased", "oldDesc": "(III)Z", "newName": "keyReleased", "newDesc": "(" + KE + ")Z", "unpack": KEYS},
    {"oldName": "charTyped", "oldDesc": "(CI)Z", "newName": "charTyped", "newDesc": "(" + CE + ")Z", "unpack": [["net/minecraft/client/input/CharacterEvent", "codepoint", "()I"], "0"]},
    {"oldName": "mouseClicked", "oldDesc": "(DDI)Z", "newName": "mouseClicked", "newDesc": "(" + ME + "Z)Z", "unpack": XY + [BTN]},
    {"oldName": "mouseReleased", "oldDesc": "(DDI)Z", "newName": "mouseReleased", "newDesc": "(" + ME + ")Z", "unpack": XY + [BTN]},
    {"oldName": "mouseDragged", "oldDesc": "(DDIDD)Z", "newName": "mouseDragged", "newDesc": "(" + ME + "DD)Z", "unpack": XY + [BTN, "p2", "p3"]},
    {"oldName": "onClick", "oldDesc": "(DD)V", "newName": "onClick", "newDesc": "(" + ME + "Z)V", "unpack": XY},
    {"oldName": "onRelease", "oldDesc": "(DD)V", "newName": "onRelease", "newDesc": "(" + ME + ")V", "unpack": XY},
    {"oldName": "onDrag", "oldDesc": "(DDDD)V", "newName": "onDrag", "newDesc": "(" + ME + "DD)V", "unpack": XY + ["p2", "p3"]},
    {"oldName": "isValidClickButton", "oldDesc": "(I)Z", "newName": "isValidClickButton", "newDesc": "(" + MI + ")Z", "unpack": [["net/minecraft/client/input/MouseButtonInfo", "button", "()I"]]},
]
# shader getters: method-reference only
getters = sorted({m.split("(")[0] for m in old["net.minecraft.client.renderer.GameRenderer"] if m.endswith("Lnet/minecraft/client/renderer/ShaderInstance;") and m.split("(")[0].startswith("get")})
j.setdefault("handleRedirects", {})["net/minecraft/client/renderer/GameRenderer"] = {g + "()Lnet/minecraft/client/renderer/ShaderInstance;": ["foxgrade/shim/ShaderCompat", g, "()Ljava/lang/Object;"] for g in getters}
# entry hooks: any mod method drawing a GUI frame tells GuiCompat which frame
hook = [GC, "current", "(" + GGE + ")V"]
j["entryHooks"] = {k: hook for k in ["render(" + GGE + "IIF)V", "renderWidget(" + GGE + "IIF)V", "renderBackground(" + GGE + "IIF)V",
    "renderBg(" + GGE + "FII)V", "renderLabels(" + GGE + "II)V", "renderTooltip(" + GGE + "II)V", "renderContents(" + GGE + "IIF)V",
    "onHudRender(" + GGE + "Lnet/minecraft/client/DeltaTracker;)V", "renderHud(" + GGE + "Lnet/minecraft/client/DeltaTracker;)V"]}
j.setdefault("renames", {}).setdefault("com/mojang/blaze3d/platform/Window", {})["getWindow"] = "handle"
cr = j.setdefault("callRedirects", {})
cr.setdefault("net/minecraft/client/gui/screens/Screen", {}).update({n + "()Z": [IC, n, "()Z"] for n in ("hasShiftDown", "hasControlDown", "hasAltDown")})
cr["net/minecraft/client/gui/screens/Screen"].update({n + "(I)Z": [IC, n, "(I)Z"] for n in ("isCopy", "isPaste", "isCut", "isSelectAll")})
cr.setdefault("com/mojang/blaze3d/platform/InputConstants", {}).update({"isKeyDown(JI)Z": [IC, "isKeyDown", "(JI)Z"],
    "getKey(II)Lcom/mojang/blaze3d/platform/InputConstants$Key;": [IC, "getKey", "(II)Lcom/mojang/blaze3d/platform/InputConstants$Key;"]})
cr.setdefault("net/minecraft/client/KeyMapping", {}).update({"matches(II)Z": [IC, "matches", "(Lnet/minecraft/client/KeyMapping;II)Z"], "matchesMouse(I)Z": [IC, "matchesMouse", "(Lnet/minecraft/client/KeyMapping;I)Z"]})
# GlStateManager's blend-factor enums: unmapped inner classes (class_4535 / class_4534) that no
# longer exist; renamed onto shims, then the enum overloads of RenderSystem land on the compat.
SF = "foxgrade/shim/SourceFactorShim"; DF = "foxgrade/shim/DestFactorShim"
j.setdefault("classRenames", {}).update({"com/mojang/blaze3d/platform/GlStateManager$class_4535": SF, "com/mojang/blaze3d/platform/GlStateManager$class_4534": DF,
    "com/mojang/blaze3d/platform/GlStateManager$SourceFactor": SF, "com/mojang/blaze3d/platform/GlStateManager$DestFactor": DF})
cr.setdefault("com/mojang/blaze3d/systems/RenderSystem", {}).update({
    "blendFunc(L" + SF + ";L" + DF + ";)V": ["foxgrade/shim/RenderSystemCompat", "blendFunc", "(L" + SF + ";L" + DF + ";)V"],
    "blendFuncSeparate(L" + SF + ";L" + DF + ";L" + SF + ";L" + DF + ";)V": ["foxgrade/shim/RenderSystemCompat", "blendFuncSeparate", "(L" + SF + ";L" + DF + ";L" + SF + ";L" + DF + ";)V"]})
CF = "Lnet/minecraft/ChatFormatting;"; CFC = "foxgrade/shim/ChatFormattingCompat"
cr.setdefault("net/minecraft/ChatFormatting", {}).update({
    "getChar()C": [CFC, "getChar", "(" + CF + ")C"], "getName()Ljava/lang/String;": [CFC, "getName", "(" + CF + ")Ljava/lang/String;"],
    "getSerializedName()Ljava/lang/String;": [CFC, "getSerializedName", "(" + CF + ")Ljava/lang/String;"],
    "isColor()Z": [CFC, "isColor", "(" + CF + ")Z"], "isFormat()Z": [CFC, "isFormat", "(" + CF + ")Z"], "getId()I": [CFC, "getId", "(" + CF + ")I"],
    "getColor()Ljava/lang/Integer;": [CFC, "getColor", "(" + CF + ")Ljava/lang/Integer;"],
    "getById(I)" + CF: [CFC, "getById", "(I)" + CF], "getByName(Ljava/lang/String;)" + CF: [CFC, "getByName", "(Ljava/lang/String;)" + CF]})
cr.setdefault("net/minecraft/client/resources/language/I18n", {}).update({
    "exists(Ljava/lang/String;)Z": ["foxgrade/shim/I18nCompat", "exists", "(Ljava/lang/String;)Z"],
    "getOrDefault(Ljava/lang/String;)Ljava/lang/String;": ["foxgrade/shim/I18nCompat", "getOrDefault", "(Ljava/lang/String;)Ljava/lang/String;"],
    "getOrDefault(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;": ["foxgrade/shim/I18nCompat", "getOrDefault", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"]})
cr.setdefault("net/minecraft/client/Minecraft", {})["getToasts()Lnet/minecraft/client/gui/components/toasts/ToastManager;"] = ["foxgrade/shim/MinecraftCompat", "getToasts", "(Lnet/minecraft/client/Minecraft;)Lnet/minecraft/client/gui/components/toasts/ToastManager;"]
j["descWidenings"].setdefault("net/minecraft/advancements/predicates/ItemPredicate", {})["test(Lnet/minecraft/world/item/ItemStack;)Z"] = "(Lnet/minecraft/world/item/ItemInstance;)Z"
CAM = "Lnet/minecraft/client/Camera;"
cr.setdefault("net/minecraft/client/Camera", {}).update({n + "()Lorg/joml/Vector3f;": ["foxgrade/shim/CameraCompat", n, "(" + CAM + ")Lorg/joml/Vector3f;"] for n in ("getLookVector", "getUpVector", "getLeftVector")})
cr.setdefault("com/mojang/blaze3d/platform/Window", {})["getGuiScale()D"] = ["foxgrade/shim/WindowCompat", "getGuiScale", "(Lcom/mojang/blaze3d/platform/Window;)D"]
for o in ["net/minecraft/client/gui/components/AbstractSelectionList", "net/minecraft/client/gui/components/ObjectSelectionList", "net/minecraft/client/gui/components/ContainerObjectSelectionList"]:
    j["renames"].setdefault(o, {}).update({"getMaxPosition": "contentHeight", "getScrollbarPosition": "scrollBarX"})
cr.setdefault("net/minecraft/client/gui/Font", {})["wordWrapHeight(Ljava/lang/String;I)I"] = ["foxgrade/shim/FontCompat", "wordWrapHeight", "(Lnet/minecraft/client/gui/Font;Ljava/lang/String;I)I"]
# ---- render* -> extract*: 26.2 renamed the whole GUI draw family, parameters unchanged ----
SPECIAL = {"render": "extractRenderState", "renderWidget": "extractWidgetRenderState", "renderWithTooltip": "extractRenderStateWithTooltipAndSubtitles",
           "renderTooltip": "extractTooltip", "renderLabels": "extractLabels"}
j.setdefault("inheritedRenames", {}); nren = 0
for oc, sigs in old.items():
    if not oc.startswith("net.minecraft.client.gui."): continue
    tgt = REN.get(oc.replace(".", "/"), oc.replace(".", "/"))
    if tgt not in new: continue
    have = set(new[tgt]["m"])
    for m in sigs:
        name, desc = m.split("(", 1); desc = "(" + desc
        if not name.startswith("render") or name.startswith("render$"): continue
        d2 = ren_desc(desc)
        if name + d2 in have: continue
        cand = SPECIAL.get(name) or ("extract" + name[len("render"):])
        if cand + d2 in have:
            j["renames"].setdefault(tgt, {})[name] = cand
            j["inheritedRenames"][name + d2] = cand; nren += 1
# Under AbstractButton the overridable draw hook is extractContents (its extractWidgetRenderState
# is final); nearest ancestor wins for calls, and declarations pick by ancestry.
for o in ["AbstractButton", "Button", "ImageButton", "PlainTextButton", "SpriteIconButton", "Checkbox", "CycleButton"]:
    j["renames"].setdefault("net/minecraft/client/gui/components/" + o, {})["renderWidget"] = "extractContents"
j["inheritedRenamesByAncestor"] = {"renderWidget(" + GGE + "IIF)V": [["net/minecraft/client/gui/components/AbstractButton", "extractContents"]]}
j["entryHooks"]["extractContents(" + GGE + "IIF)V"] = hook
# list / scroll helpers that only changed name
for o in ["net/minecraft/client/gui/components/AbstractSelectionList", "net/minecraft/client/gui/components/ObjectSelectionList",
          "net/minecraft/client/gui/components/ContainerObjectSelectionList", "net/minecraft/client/gui/components/AbstractScrollArea"]:
    j["renames"].setdefault(o, {}).update({"getScrollAmount": "scrollAmount", "getMaxScroll": "maxScrollAmount", "ensureVisible": "scrollToEntry",
                                          "getMaxPosition": "contentHeight", "getScrollbarPosition": "scrollBarX"})
j["inheritedRenames"].update({"getScrollAmount()D": "scrollAmount", "getMaxScroll()I": "maxScrollAmount", "getMaxPosition()I": "contentHeight", "getScrollbarPosition()I": "scrollBarX"})
# entry hooks must name the methods as they are AFTER the rename
for k in ["extractRenderState(" + GGE + "IIF)V", "extractWidgetRenderState(" + GGE + "IIF)V", "extractBackground(" + GGE + "IIF)V",
          "extractContents(" + GGE + "IIF)V", "extractLabels(" + GGE + "II)V", "extractTooltip(" + GGE + "II)V"]: j["entryHooks"][k] = hook
# ---- recurring small bridges ----
ENTRY = "Lnet/minecraft/client/gui/components/AbstractSelectionList$Entry;"; ASL = "Lnet/minecraft/client/gui/components/AbstractSelectionList;"
cr.setdefault("net/minecraft/client/gui/components/AbstractSelectionList", {}).update({
    "getEntry(I)" + ENTRY: ["foxgrade/shim/ListCompat", "getEntry", "(" + ASL + "I)" + ENTRY], "remove(I)" + ENTRY: ["foxgrade/shim/ListCompat", "remove", "(" + ASL + "I)" + ENTRY],
    "removeEntry(" + ENTRY + ")Z": ["foxgrade/shim/ListCompat", "removeEntry", "(" + ASL + ENTRY + ")Z"], "updateScrollingState(DDI)V": ["foxgrade/shim/ListCompat", "updateScrollingState", "(" + ASL + "DDI)V"]})
LFC = "foxgrade/shim/ListFieldCompat"
j["fieldRedirects"].setdefault("net/minecraft/client/gui/components/AbstractSelectionList", {}).update({
    "get headerHeight:I": [LFC, "headerHeight", "(" + ASL + ")I"], "put headerHeight:I": [LFC, "setHeaderHeight", "(" + ASL + "I)V"],
    "get itemHeight:I": [LFC, "itemHeight", "(" + ASL + ")I"], "put itemHeight:I": [LFC, "setItemHeight", "(" + ASL + "I)V"]})
cr["net/minecraft/client/gui/components/AbstractSelectionList"].update({"clickedHeader(II)Z": [LFC, "clickedHeader", "(" + ASL + "II)Z"], "setRenderHeader(ZI)V": [LFC, "setRenderHeader", "(" + ASL + "ZI)V"]})
j["renames"].setdefault("net/fabricmc/fabric/api/client/screen/v1/Screens", {})["getButtons"] = "getWidgets"
cr["net/minecraft/client/gui/components/AbstractSelectionList"]["children()Ljava/util/List;"] = [LFC, "children", "(" + ASL + ")Ljava/util/List;"]
for old_n, new_n in [("C2SPlayChannelEvents", "ServerboundPlayChannelEvents"), ("C2SConfigurationChannelEvents", "ServerboundConfigurationChannelEvents")]:
    for suf in ("", "$Register", "$Unregister"): j["classRenames"]["net/fabricmc/fabric/api/client/networking/v1/" + old_n + suf] = "net/fabricmc/fabric/api/client/networking/v1/" + new_n + suf
j["classRenames"]["net/fabricmc/fabric/api/client/command/v2/ClientCommandManager"] = "net/fabricmc/fabric/api/client/command/v2/ClientCommands"
j["classRenames"]["net/fabricmc/fabric/api/client/rendering/v1/TooltipComponentCallback"] = "net/fabricmc/fabric/api/client/rendering/v1/ClientTooltipComponentCallback"
OPT = "Lnet/minecraft/client/Options;"
j["fieldRedirects"].setdefault("net/minecraft/server/level/ServerPlayer", {})["get server:Lnet/minecraft/server/MinecraftServer;"] = ["foxgrade/shim/PlayerCompat", "server", "(Lnet/minecraft/server/level/ServerPlayer;)Lnet/minecraft/server/MinecraftServer;"]
# List entries: render(g, index, y, x, rowWidth, rowHeight, mouseX, mouseY, hovered, delta) became
# extractContent(g, mouseX, mouseY, hovered, delta); the entry now knows its own rectangle.
j["overrideAdapters"].append({"oldName": "render", "oldDesc": "(" + GGE + "IIIIIIIZF)V", "newName": "extractContent", "newDesc": "(" + GGE + "IIZF)V",
    "unpack": ["p1", "0", ["this", "getY", "()I"], ["this", "getX", "()I"], ["this", "getWidth", "()I"], ["this", "getHeight", "()I"], "p2", "p3", "p4", "p5"]})
j["entryHooks"]["extractContent(" + GGE + "IIZF)V"] = hook
cr.setdefault("net/minecraft/client/Options", {})["touchscreen()Lnet/minecraft/client/OptionInstance;"] = ["foxgrade/shim/OptionsCompat", "touchscreen", "(" + OPT + ")Lnet/minecraft/client/OptionInstance;"]
j["fieldRedirects"].setdefault("net/minecraft/client/Options", {}).update({"get hideGui:Z": ["foxgrade/shim/OptionsCompat", "hideGui", "(" + OPT + ")Z"], "put hideGui:Z": ["foxgrade/shim/OptionsCompat", "setHideGui", "(" + OPT + "Z)V"]})
EV = "Lnet/fabricmc/fabric/api/event/Event;"; FEC = "foxgrade/shim/FabricEventsCompat"
j["fieldRedirects"].setdefault("net/fabricmc/fabric/api/entity/event/v1/ServerEntityLevelChangeEvents", {}).update({
    "getstatic AFTER_ENTITY_CHANGE_WORLD:" + EV: [FEC, "afterEntityChangeWorld", "()" + EV], "getstatic AFTER_PLAYER_CHANGE_WORLD:" + EV: [FEC, "afterPlayerChangeWorld", "()" + EV]})
# Resource reload listeners: the interface method was reshaped; a mod implementing the 1.21.x
# signature gets the 26.2 one synthesised, feeding it the barrier, the shared state's resource
# manager, the current profiler twice, and the two executors.
PRL = "Lnet/minecraft/server/packs/resources/PreparableReloadListener$"; EX = "Ljava/util/concurrent/Executor;"; PF = "Lnet/minecraft/util/profiling/ProfilerFiller;"
j["overrideAdapters"].append({"oldName": "reload", "oldDesc": "(" + PRL + "PreparationBarrier;Lnet/minecraft/server/packs/resources/ResourceManager;" + PF + PF + EX + EX + ")Ljava/util/concurrent/CompletableFuture;",
    "newName": "reload", "newDesc": "(" + PRL + "SharedState;" + EX + PRL + "PreparationBarrier;" + EX + ")Ljava/util/concurrent/CompletableFuture;",
    "unpack": ["p3", ["net/minecraft/server/packs/resources/PreparableReloadListener$SharedState", "resourceManager", "()Lnet/minecraft/server/packs/resources/ResourceManager;"],
               ["static", "net/minecraft/util/profiling/Profiler", "get", "()" + PF], ["static", "net/minecraft/util/profiling/Profiler", "get", "()" + PF], "p2", "p4"]})
SCR = "Lnet/minecraft/client/gui/screens/Screen;"
cr.setdefault("net/minecraft/client/gui/screens/Screen", {}).update({"setTooltipForNextRenderPass(Lnet/minecraft/network/chat/Component;)V": ["foxgrade/shim/ScreenCompat", "setTooltipForNextRenderPass", "(" + SCR + "Lnet/minecraft/network/chat/Component;)V"],
    "setTooltipForNextRenderPass(Ljava/util/List;)V": ["foxgrade/shim/ScreenCompat", "setTooltipForNextRenderPass", "(" + SCR + "Ljava/util/List;)V"], "clearTooltipForNextRenderPass()V": ["foxgrade/shim/ScreenCompat", "clearTooltipForNextRenderPass", "(" + SCR + ")V"]})
cr.setdefault("net/minecraft/world/entity/player/PlayerSkin", {})["texture()Lnet/minecraft/resources/Identifier;"] = ["foxgrade/shim/SkinCompat", "texture", "(Lnet/minecraft/world/entity/player/PlayerSkin;)Lnet/minecraft/resources/Identifier;"]
j["renames"].setdefault("com/mojang/authlib/GameProfile", {}).update({"getId": "id", "getName": "name"})
for suf in ("", "$AfterEntityChange", "$AfterPlayerChange"): j["classRenames"]["net/fabricmc/fabric/api/entity/event/v1/ServerEntityWorldChangeEvents" + suf] = "net/fabricmc/fabric/api/entity/event/v1/ServerEntityLevelChangeEvents" + suf
CT = "Lnet/minecraft/nbt/CompoundTag;"; LT = "Lnet/minecraft/nbt/ListTag;"; S = "Ljava/lang/String;"; NB = "foxgrade/shim/NbtCompat"
nbt = {"getBoolean(" + S + ")Z": "Z", "getByte(" + S + ")B": "B", "getShort(" + S + ")S": "S", "getInt(" + S + ")I": "I", "getLong(" + S + ")J": "J", "getFloat(" + S + ")F": "F",
       "getDouble(" + S + ")D": "D", "getString(" + S + ")" + S: S, "getCompound(" + S + ")" + CT: CT, "getList(" + S + "I)" + LT: LT, "getIntArray(" + S + ")[I": "[I",
       "getLongArray(" + S + ")[J": "[J", "getByteArray(" + S + ")[B": "[B", "contains(" + S + "I)Z": "Z"}
cr.setdefault("net/minecraft/nbt/CompoundTag", {}).update({k: [NB, k.split("(")[0], "(" + CT + k[k.index("(") + 1:]] for k in nbt})
lnbt = ["getCompound(I)" + CT, "getString(I)" + S, "getInt(I)I", "getFloat(I)F", "getDouble(I)D"]
cr.setdefault("net/minecraft/nbt/ListTag", {}).update({k: [NB, k.split("(")[0], "(" + LT + k[k.index("(") + 1:]] for k in lnbt})
IR = "Lnet/minecraft/world/InteractionResult;"
j.setdefault("fieldRedirects", {}).setdefault("net/minecraft/world/InteractionResult", {}).update({"getstatic " + n + ":" + IR: ["foxgrade/shim/InteractionCompat", n, "()" + IR] for n in ("SUCCESS", "SUCCESS_NO_ITEM_USED", "CONSUME", "CONSUME_PARTIAL", "PASS", "FAIL")})
cr.setdefault("net/minecraft/client/sounds/SoundManager", {})["play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V"] = ["foxgrade/shim/SoundCompat", "play", "(Lnet/minecraft/client/sounds/SoundManager;Lnet/minecraft/client/resources/sounds/SoundInstance;)V"]
for _k in ("getPixelRGBA", "setPixelRGBA"): j["renames"].get("com/mojang/blaze3d/platform/NativeImage", {}).pop(_k, None)   # superseded by redirects
NIM = "Lcom/mojang/blaze3d/platform/NativeImage;"
cr.setdefault("com/mojang/blaze3d/platform/NativeImage", {}).update({"blendPixel(III)V": ["foxgrade/shim/ImageCompat", "blendPixel", "(" + NIM + "III)V"],
    "getPixelRGBA(II)I": ["foxgrade/shim/ImageCompat", "getPixelRGBA", "(" + NIM + "II)I"], "setPixelRGBA(III)V": ["foxgrade/shim/ImageCompat", "setPixelRGBA", "(" + NIM + "III)V"]})
NI = "Lcom/mojang/blaze3d/platform/NativeImage;"
j.setdefault("ctorAdapters", {}).setdefault("net/minecraft/client/renderer/texture/DynamicTexture", {})["(" + NI + ")V"] = {"newDesc": "(Ljava/util/function/Supplier;" + NI + ")V", "transforms": [{"slot": -1, "via": ["foxgrade/shim/TextureCompat", "name", "()Ljava/util/function/Supplier;"]}]}
cr.setdefault("net/minecraft/client/renderer/texture/DynamicTexture", {})["setFilter(ZZ)V"] = ["foxgrade/shim/TextureCompat", "setFilter", "(Lnet/minecraft/client/renderer/texture/AbstractTexture;ZZ)V"]
cr.setdefault("net/minecraft/world/item/ItemStack", {})["is(Lnet/minecraft/world/item/Item;)Z"] = ["foxgrade/shim/ItemCompat", "is", "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/Item;)Z"]
cr.setdefault("net/minecraft/world/item/Item", {})["getDescription()Lnet/minecraft/network/chat/Component;"] = ["foxgrade/shim/ItemCompat", "getDescription", "(Lnet/minecraft/world/item/Item;)Lnet/minecraft/network/chat/Component;"]
cr.setdefault("net/minecraft/world/level/Level", {})["getDayTime()J"] = ["foxgrade/shim/LevelCompat", "getDayTime", "(Lnet/minecraft/world/level/Level;)J"]
for _o in ("net/minecraft/world/level/block/state/BlockState", "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase"):
    cr.setdefault(_o, {})["isSolidRender(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z"] = ["foxgrade/shim/BlockApiCompat", "isSolidRender", "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z"]
PMLP = "net/fabricmc/fabric/api/client/model/loading/v1/PreparableModelLoadingPlugin"
j["renames"].setdefault(PMLP, {})["onInitializeModelLoader"] = "initialize"
j.setdefault("samRenames", {}).setdefault(PMLP, {})["onInitializeModelLoader"] = "initialize"
j.setdefault("inheritedRenamesByAncestor", {})["onInitializeModelLoader(Ljava/lang/Object;Lnet/fabricmc/fabric/api/client/model/loading/v1/ModelLoadingPlugin$Context;)V"] = [[PMLP, "initialize"]]
cr.setdefault(PMLP, {})["register(L" + PMLP + "$DataLoader;L" + PMLP + ";)V"] = ["foxgrade/shim/ModelCompat", "registerPreparable", "(Ljava/lang/Object;Ljava/lang/Object;)V"]
BIO = "Lnet/minecraft/world/level/biome/Biome;"; BP = "Lnet/minecraft/core/BlockPos;"
cr.setdefault("net/minecraft/world/level/biome/Biome", {}).update({
    "coldEnoughToSnow(" + BP + ")Z": ["foxgrade/shim/LevelCompat", "coldEnoughToSnow", "(" + BIO + BP + ")Z"],
    "warmEnoughToRain(" + BP + ")Z": ["foxgrade/shim/LevelCompat", "warmEnoughToRain", "(" + BIO + BP + ")Z"],
    "getTemperature(" + BP + ")F": ["foxgrade/shim/LevelCompat", "biomeTemperature", "(" + BIO + BP + ")F"]})
# a CLASS implementing ScreenEvents.BeforeRender/AfterRender declares beforeRender(...): rename the declaration itself
SCR = "Lnet/minecraft/client/gui/screens/Screen;"
GGO = "Lnet/minecraft/client/gui/GuiGraphics;"
for _d in (GGE, GGO): j["inheritedRenames"].update({"beforeRender(" + SCR + _d + "IIF)V": "beforeExtract", "afterRender(" + SCR + _d + "IIF)V": "afterExtract"})
j["classRenames"]["net/minecraft/data/tags/TagsProvider$TagAppender"] = "net/minecraft/data/tags/TagAppender"
j["classRenames"]["net/minecraft/commands/arguments/item/ItemParser$ItemResult"] = "net/minecraft/commands/arguments/item/ItemInput"
j["classRenames"]["net/minecraft/client/player/Input"] = "net/minecraft/client/player/ClientInput"   # freecam   # same item()/components() accessors (carry-on)   # data-gen appender became top-level (puzzleslib touches it at runtime)
FBB = "Lnet/minecraft/network/FriendlyByteBuf;"
cr.setdefault("net/minecraft/network/FriendlyByteBuf", {}).update({"writeDate(Ljava/util/Date;)" + FBB: ["foxgrade/shim/BufCompat", "writeDate", "(" + FBB + "Ljava/util/Date;)" + FBB], "readDate()Ljava/util/Date;": ["foxgrade/shim/BufCompat", "readDate", "(" + FBB + ")Ljava/util/Date;"]})
LVL = "Lnet/minecraft/world/level/Level;"
cr.setdefault("net/minecraft/world/level/Level", {}).update({"getSunAngle(F)F": ["foxgrade/shim/LevelCompat", "sunAngle", "(" + LVL + "F)F"], "getTimeOfDay(F)F": ["foxgrade/shim/LevelCompat", "timeOfDay", "(" + LVL + "F)F"]})
CSS = "Lnet/minecraft/commands/CommandSourceStack;"
cr.setdefault("net/minecraft/world/entity/Entity", {})["createCommandSourceStack()" + CSS] = ["foxgrade/shim/EntityLegacyCompat", "commandSourceStack", "(Lnet/minecraft/world/entity/Entity;)" + CSS]
SEV_ = "Lnet/minecraft/sounds/SoundEvent;"
for _o, _n in (("LEASH_KNOT_BREAK", "LEAD_BREAK"), ("LEASH_KNOT_PLACE", "LEAD_TIED")):   # 26.2 renamed leash knots to leads
    j["fieldRedirects"].setdefault("net/minecraft/sounds/SoundEvents", {})["getstatic " + _o + ":" + SEV_] = ["move", "net/minecraft/sounds/SoundEvents", SEV_, _n]
cr.setdefault("net/minecraft/WorldVersion", {})["getPackVersion(Lnet/minecraft/server/packs/PackType;)I"] = ["foxgrade/shim/VersionCompat", "packVersion", "(Lnet/minecraft/WorldVersion;Lnet/minecraft/server/packs/PackType;)I"]
VSH = "Lnet/minecraft/world/phys/shapes/VoxelShape;"
j["fieldRedirects"].setdefault("net/minecraft/world/level/block/ChestBlock", {})["getstatic AABB:" + VSH] = ["move", "net/minecraft/world/level/block/ChestBlock", VSH, "SHAPE"]   # lootr
# value-provider bounded codecs moved to the plural holder classes (YUNG's API)
cr.setdefault("net/minecraft/util/valueproviders/IntProvider", {})["codec(II)Lcom/mojang/serialization/Codec;"] = ["net/minecraft/util/valueproviders/IntProviders", "codec", "(II)Lcom/mojang/serialization/Codec;"]
cr.setdefault("net/minecraft/util/valueproviders/FloatProvider", {})["codec(FF)Lcom/mojang/serialization/Codec;"] = ["net/minecraft/util/valueproviders/FloatProviders", "codec", "(FF)Lcom/mojang/serialization/Codec;"]
ESV = "Ljava/util/concurrent/ExecutorService;"
cr.setdefault("net/minecraft/util/Util", {}).update({"backgroundExecutor()" + ESV: ["foxgrade/shim/ExecCompat", "backgroundExecutor", "()" + ESV], "ioPool()" + ESV: ["foxgrade/shim/ExecCompat", "ioPool", "()" + ESV], "nonCriticalIoPool()" + ESV: ["foxgrade/shim/ExecCompat", "nonCriticalIoPool", "()" + ESV]})
j["classRenames"]["net/minecraft/client/gui/screens/inventory/EffectRenderingInventoryScreen"] = "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen"   # 26.2 folded effect rendering into the container screen
OVL = "Lnet/minecraft/client/gui/screens/Overlay;"; MCC = "Lnet/minecraft/client/Minecraft;"
cr.setdefault("net/minecraft/client/Minecraft", {}).update({"getOverlay()" + OVL: ["foxgrade/shim/MinecraftCompat", "getOverlay", "(" + MCC + ")" + OVL], "setOverlay(" + OVL + ")V": ["foxgrade/shim/MinecraftCompat", "setOverlay", "(" + MCC + OVL + ")V"]})
cr.setdefault("net/minecraft/world/entity/LivingEntity", {})["getAllSlots()Ljava/lang/Iterable;"] = ["foxgrade/shim/EntityLegacyCompat", "getAllSlots", "(Lnet/minecraft/world/entity/LivingEntity;)Ljava/lang/Iterable;"]
HSN = "Lnet/minecraft/core/HolderSet$Named;"; TGK = "Lnet/minecraft/tags/TagKey;"
for _o in ("net/minecraft/core/Registry", "net/minecraft/core/DefaultedRegistry", "net/minecraft/core/MappedRegistry", "net/minecraft/core/DefaultedMappedRegistry"):
    cr.setdefault(_o, {})["getOrCreateTag(" + TGK + ")" + HSN] = ["foxgrade/shim/RegistryCompat", "getOrCreateTag", "(Lnet/minecraft/core/Registry;" + TGK + ")" + HSN]
j["renames"].setdefault("com/mojang/serialization/Dynamic", {})["value"] = "getValue"   # DFU 9
j["fieldRedirects"].setdefault("net/fabricmc/fabric/api/resource/ResourceReloadListenerKeys", {})["getstatic TAGS:Lnet/minecraft/resources/Identifier;"] = ["foxgrade/shim/FabricCompat", "reloadKeyTags", "()Lnet/minecraft/resources/Identifier;"]
MCD = "Lcom/mojang/serialization/MapCodec;"; LTC = "foxgrade/shim/LootTypeCompat"
for _t in ("Lnet/minecraft/world/level/storage/loot/entries/LootPoolEntryType;", "Lnet/minecraft/world/level/storage/loot/functions/LootItemFunctionType;", "Lnet/minecraft/world/level/storage/loot/predicates/LootItemConditionType;"):
    j["overrideAdapters"].append({"oldName": "getType", "oldDesc": "()" + _t, "newName": "codec", "newDesc": "()" + MCD, "unpack": [], "convert": ["static", LTC, "unwrap", "(Ljava/lang/Object;)" + MCD]})
RGY = "Lnet/minecraft/core/Registry;"; RKY = "Lnet/minecraft/resources/ResourceKey;"; IDF = "Lnet/minecraft/resources/Identifier;"
for _o in ("net/minecraft/core/Registry", "net/minecraft/core/DefaultedRegistry", "net/minecraft/core/MappedRegistry", "net/minecraft/core/DefaultedMappedRegistry", "net/minecraft/core/WritableRegistry"):
    cr.setdefault(_o, {}).update({"get(" + IDF + ")Ljava/lang/Object;": ["foxgrade/shim/RegistryCompat", "getValue", "(" + RGY + IDF + ")Ljava/lang/Object;"],
        "get(" + RKY + ")Ljava/lang/Object;": ["foxgrade/shim/RegistryCompat", "getValue", "(" + RGY + RKY + ")Ljava/lang/Object;"],
        "getOrThrow(" + RKY + ")Ljava/lang/Object;": ["foxgrade/shim/RegistryCompat", "getValueOrThrow", "(" + RGY + RKY + ")Ljava/lang/Object;"]})
ETB = "Lnet/minecraft/world/entity/EntityType$Builder;"; ETY = "Lnet/minecraft/world/entity/EntityType;"
cr.setdefault("net/minecraft/world/entity/EntityType$Builder", {}).update({"build()" + ETY: ["foxgrade/shim/RegistryCompat", "buildEntityType", "(" + ETB + ")" + ETY], "build(Ljava/lang/String;)" + ETY: ["foxgrade/shim/RegistryCompat", "buildEntityType", "(" + ETB + "Ljava/lang/String;)" + ETY]})
ETP = "Lnet/minecraft/advancements/predicates/entity/EntityTypePredicate;"
cr.setdefault("net/minecraft/advancements/predicates/entity/EntityTypePredicate", {})["matches(" + ETY + ")Z"] = ["foxgrade/shim/EntityLegacyCompat", "predicateMatches", "(" + ETP + ETY + ")Z"]
BST = "Lnet/minecraft/world/level/block/state/BlockState;"; BTG = "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"; DIR = "Lnet/minecraft/core/Direction;"
for _o in ("net/minecraft/world/level/block/state/BlockState", "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase"):
    cr.setdefault(_o, {})["getAppearance(" + BTG + BP + DIR + BST + BP + ")" + BST] = ["foxgrade/shim/BlockApiCompat", "getAppearance", "(" + BST + BTG + BP + DIR + BST + BP + ")" + BST]
j["fieldRedirects"].setdefault("net/minecraft/world/level/levelgen/DensityFunction", {})["getstatic HOLDER_HELPER_CODEC:Lcom/mojang/serialization/Codec;"] = ["move", "net/minecraft/world/level/levelgen/DensityFunction", "Lcom/mojang/serialization/Codec;", "CODEC"]
RIL = "Lnet/minecraft/resources/RegistryOps$RegistryInfoLookup;"; HLP = "Lnet/minecraft/core/HolderLookup$Provider;"
j["overrideAdapters"].append({"oldName": "test", "oldDesc": "(" + HLP + ")Z", "newName": "test", "newDesc": "(" + RIL + ")Z", "unpack": [["static", "foxgrade/shim/ConditionCompat", "provider", "(" + RIL + ")" + HLP, "p1"]]})   # Fabric ResourceCondition
for _o in ("fluid/FluidVariant", "item/ItemVariant", "storage/TransferVariant"):
    j["renames"].setdefault("net/fabricmc/fabric/api/transfer/v1/" + _o, {})["getComponents"] = "getComponentsPatch"   # 1.21's getComponents() returned the patch
j["classRenames"]["net/minecraft/world/item/crafting/SimpleCraftingRecipeSerializer"] = "net/minecraft/world/item/crafting/RecipeSerializer"
RSZ = "Lnet/minecraft/world/item/crafting/RecipeSerializer;"; RSC = "foxgrade/shim/RecipeSerializerCompat"
j["ctorAdapters"].setdefault("net/minecraft/world/item/crafting/RecipeSerializer", {})["(Ljava/util/function/Function;)V"] = {"newDesc": "(Lcom/mojang/serialization/MapCodec;Lnet/minecraft/network/codec/StreamCodec;)V",
    "args": [["static", RSC, "simpleMapCodec", "(Ljava/util/function/Function;)Lcom/mojang/serialization/MapCodec;", "o1"], ["static", RSC, "simpleStreamCodec", "(Ljava/util/function/Function;)Lnet/minecraft/network/codec/StreamCodec;", "o1"]],
    "factory": [RSC, "simpleSerializer", "(Ljava/util/function/Function;)" + RSZ]}
cr.setdefault("net/minecraft/core/Registry", {})["register(" + RGY + "Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;"] = ["foxgrade/shim/RegistryCompat", "register", "(" + RGY + "Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;"]
BLK = "Lnet/minecraft/world/level/block/Block;"; IPP = "Lnet/minecraft/world/item/Item$Properties;"
j["ctorAdapters"].setdefault("net/minecraft/world/item/StandingAndWallBlockItem", {})["(" + BLK + BLK + IPP + DIR + ")V"] = {"newDesc": "(" + BLK + BLK + DIR + IPP + ")V", "args": [["o1"], ["o2"], ["o4"], ["o3"]]}   # 26.2 swapped the last two
cr.setdefault("net/minecraft/client/Minecraft", {})["getProfiler()Lnet/minecraft/util/profiling/ProfilerFiller;"] = ["foxgrade/shim/MinecraftCompat", "getProfiler", "(" + MCC + ")Lnet/minecraft/util/profiling/ProfilerFiller;"]
j["classRenames"]["net/minecraft/world/level/biome/AmbientParticleSettings"] = "net/minecraft/world/attribute/AmbientParticle"
SPT_ = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureProcessorType;"
j["overrideAdapters"].append({"oldName": "getType", "oldDesc": "()" + SPT_, "newName": "codec", "newDesc": "()" + MCD, "unpack": [], "convert": ["static", LTC, "unwrap", "(Ljava/lang/Object;)" + MCD]})   # StructureProcessor.getType() returns the MapCodec now
RMI = "net/fabricmc/fabric/impl/resource/loader/ResourceManagerHelperImpl"; RMA = "net/fabricmc/fabric/api/resource/ResourceManagerHelper"; MCT = "Lnet/fabricmc/loader/api/ModContainer;"; RAT = "Lnet/fabricmc/fabric/api/resource/ResourcePackActivationType;"
for _d in ("(" + IDF + MCT + RAT + ")Z", "(" + IDF + MCT + "Lnet/minecraft/network/chat/Component;" + RAT + ")Z", "(" + IDF + MCT + "Ljava/lang/String;" + RAT + ")Z"):
    cr.setdefault(RMI, {})["registerBuiltinResourcePack" + _d] = [RMA, "registerBuiltinResourcePack", _d]
j["fieldRedirects"].setdefault("net/minecraft/world/item/ItemStack", {})["getstatic SINGLE_ITEM_CODEC:Lcom/mojang/serialization/Codec;"] = ["move", "net/minecraft/world/item/ItemStack", "Lcom/mojang/serialization/Codec;", "CODEC"]
BBP_ = "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;"
cr.setdefault("net/minecraft/world/level/block/state/BlockBehaviour$Properties", {})["dropsLike(" + BLK + ")" + BBP_] = ["foxgrade/shim/BlockApiCompat", "dropsLike", "(" + BBP_ + BLK + ")" + BBP_]
cr.setdefault(RMI, {})["registerBuiltinResourcePack(" + IDF + "Ljava/lang/String;" + MCT + "Lnet/minecraft/network/chat/Component;" + RAT + ")Z"] = ["foxgrade/shim/FabricCompat", "registerBuiltinResourcePack", "(" + IDF + "Ljava/lang/String;" + MCT + "Lnet/minecraft/network/chat/Component;Ljava/lang/Object;)Z"]
j["classRenames"]["net/minecraft/commands/arguments/ResourceLocationArgument"] = "net/minecraft/commands/arguments/IdentifierArgument"
j["classRenames"]["net/fabricmc/fabric/impl/resource/loader/ModNioResourcePack"] = "net/fabricmc/fabric/impl/resource/pack/ModNioPackResources"   # Fabric internal, but mods reach into it
# a bridged screen's render() calling renderBackground() would blur a second time per frame (26.2 draws the background itself)
GGO_ = "Lnet/minecraft/client/gui/GuiGraphics;"
for _d in (GGE, GGO_):
    cr.setdefault("net/minecraft/client/gui/screens/Screen", {})["renderBackground(" + _d + "IIF)V"] = ["foxgrade/shim/GuiCompat", "backgroundDrawnByGame", "(" + SCR + GGE + "IIF)V"]
PMS = "net/minecraft/server/packs/metadata/pack/PackMetadataSection"; CMP = "Lnet/minecraft/network/chat/Component;"; IRG = "Lnet/minecraft/util/InclusiveRange;"
j["ctorAdapters"].setdefault(PMS, {}).update({
    "(" + CMP + "ILjava/util/Optional;)V": {"newDesc": "(" + CMP + IRG + ")V", "args": [["o1"], ["static", "foxgrade/shim/PackCompat", "range", "(ILjava/util/Optional;)" + IRG, "o2", "o3"]]},
    "(" + CMP + "I)V": {"newDesc": "(" + CMP + IRG + ")V", "args": [["o1"], ["static", "foxgrade/shim/PackCompat", "range", "(I)" + IRG, "o2"]]}})
j["fieldRedirects"].setdefault("net/minecraft/core/component/DataComponents", {})["getstatic HIDE_ADDITIONAL_TOOLTIP:Lnet/minecraft/core/component/DataComponentType;"] = ["foxgrade/shim/ItemCompat", "hideAdditionalTooltip", "()Lnet/minecraft/core/component/DataComponentType;"]
cr.setdefault("net/minecraft/client/Minecraft", {})["getGuiSprites()Lnet/minecraft/client/gui/GuiSpriteManager;"] = ["net/minecraft/client/gui/GuiSpriteManager", "of", "(Lnet/minecraft/client/Minecraft;)Lnet/minecraft/client/gui/GuiSpriteManager;"]  # 26.2: GUI sprites live in AtlasManager; shim stands in
ST = "Lnet/minecraft/client/gui/components/toasts/SystemToast;"; STID = "Lnet/minecraft/client/gui/components/toasts/SystemToast$SystemToastId;"; CO = "Lnet/minecraft/network/chat/Component;"
cr.setdefault("net/minecraft/client/gui/components/toasts/SystemToast", {})["multiline(Lnet/minecraft/client/Minecraft;" + STID + CO + CO + ")" + ST] = ["foxgrade/shim/ToastCompat", "multiline", "(Lnet/minecraft/client/Minecraft;" + STID + CO + CO + ")" + ST]
cr.setdefault("net/minecraft/client/gui/screens/inventory/tooltip/TooltipRenderUtil", {})["renderTooltipBackground(" + GGE + "IIIII)V"] = ["foxgrade/shim/TooltipCompat", "renderTooltipBackground", "(" + GGE + "IIIII)V"]
EB = "Lnet/minecraft/client/gui/components/EditBox;"
cr.setdefault("net/minecraft/client/gui/components/EditBox", {}).update({"setFilter(Ljava/util/function/Predicate;)V": ["foxgrade/shim/WidgetCompat", "setFilter", "(" + EB + "Ljava/util/function/Predicate;)V"],
    "setFormatter(Ljava/util/function/BiFunction;)V": ["foxgrade/shim/WidgetCompat", "setFormatter", "(" + EB + "Ljava/util/function/BiFunction;)V"]})
OI = "Lnet/minecraft/client/OptionInstance$"; VUL = OI + "ValueUpdateListener;"
j["ctorAdapters"].setdefault("net/minecraft/client/OptionInstance", {}).update({
    "(" + S + OI + "TooltipSupplier;" + OI + "CaptionBasedToString;" + OI + "ValueSet;Ljava/lang/Object;Ljava/util/function/Consumer;)V":
        {"newDesc": "(" + S + OI + "TooltipSupplier;" + OI + "CaptionBasedToString;" + OI + "ValueSet;Ljava/lang/Object;" + VUL + ")V", "transforms": [{"slot": 5, "via": ["foxgrade/shim/OptionInstanceCompat", "listener", "(Ljava/util/function/Consumer;)" + VUL]}]},
    "(" + S + OI + "TooltipSupplier;" + OI + "CaptionBasedToString;" + OI + "ValueSet;Lcom/mojang/serialization/Codec;Ljava/lang/Object;Ljava/util/function/Consumer;)V":
        {"newDesc": "(" + S + OI + "TooltipSupplier;" + OI + "CaptionBasedToString;" + OI + "ValueSet;Lcom/mojang/serialization/Codec;Ljava/lang/Object;" + VUL + ")V", "transforms": [{"slot": 6, "via": ["foxgrade/shim/OptionInstanceCompat", "listener", "(Ljava/util/function/Consumer;)" + VUL]}]}})
j["ctorAdapters"].setdefault("net/minecraft/client/gui/screens/options/OptionsScreen", {})["(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Options;)V"] = {"newDesc": "(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Options;Z)V", "transforms": [{"slot": -2, "via": ["foxgrade/shim/WidgetCompat", "falseValue", "()Z"]}]}
j["classRenames"].update({"net/minecraft/ResourceLocationException": "net/minecraft/IdentifierException"})
for old_n, new_n in [("S2CPlayChannelEvents", "ClientboundPlayChannelEvents"), ("S2CConfigurationChannelEvents", "ClientboundConfigurationChannelEvents")]:
    for suf in ("", "$Register", "$Unregister"): j["classRenames"]["net/fabricmc/fabric/api/networking/v1/" + old_n + suf] = "net/fabricmc/fabric/api/networking/v1/" + new_n + suf
# ---- dyed block/item collections: generate BlocksCompat.java plus getstatic redirects ----
COLORS = [("WHITE", "white"), ("ORANGE", "orange"), ("MAGENTA", "magenta"), ("LIGHT_BLUE", "lightBlue"), ("YELLOW", "yellow"), ("LIME", "lime"), ("PINK", "pink"), ("GRAY", "gray"),
          ("LIGHT_GRAY", "lightGray"), ("CYAN", "cyan"), ("PURPLE", "purple"), ("BLUE", "blue"), ("BROWN", "brown"), ("GREEN", "green"), ("RED", "red"), ("BLACK", "black")]
lines = []; BL = "Lnet/minecraft/world/level/block/Block;"; IT = "Lnet/minecraft/world/item/Item;"
CC = ":Lnet/minecraft/world/level/block/ColorCollection;"; WCC = ":Lnet/minecraft/world/level/block/WeatheringCopperCollection;"
for holder, typ, tdesc, colls in [("Blocks", "Block", BL, [f.split(":")[0] for f in new["net/minecraft/world/level/block/Blocks"]["f"] if f.endswith(CC)]),
                                  ("Items", "Item", IT, [f.split(":")[0] for f in new["net/minecraft/world/item/Items"]["f"] if f.endswith(CC)])]:
    owner = "net/minecraft/world/level/block/Blocks" if holder == "Blocks" else "net/minecraft/world/item/Items"
    for coll in colls:
        suffix = coll[len("DYED_"):] if coll.startswith("DYED_") else coll
        for up, getter in COLORS:
            fld = up + "_" + suffix; meth = holder + "_" + fld
            lines.append(f"  public static {typ} {meth}() {{ return ({typ}) {holder}.{coll}.{getter}(); }}")
            j["fieldRedirects"].setdefault(owner, {})["getstatic " + fld + ":" + tdesc] = ["foxgrade/shim/BlocksCompat", meth, "()" + tdesc]
STATES = [("", "unaffected"), ("EXPOSED_", "exposed"), ("WEATHERED_", "weathered"), ("OXIDIZED_", "oxidized")]
for holder, typ, tdesc in [("Blocks", "Block", BL), ("Items", "Item", IT)]:
    owner = "net/minecraft/world/level/block/Blocks" if holder == "Blocks" else "net/minecraft/world/item/Items"
    for coll in [f.split(":")[0] for f in new[owner]["f"] if f.endswith(WCC)]:
        for waxed, group in [("", "weathering"), ("WAXED_", "waxed")]:
            for pre, getter in STATES:
                # COPPER_BLOCK's family is irregular: EXPOSED_COPPER, not EXPOSED_COPPER_BLOCK
                base = "COPPER" if coll == "COPPER_BLOCK" and pre else coll
                fld = waxed + pre + base; meth = holder + "_" + fld
                lines.append(f"  public static {typ} {meth}() {{ return ({typ}) {holder}.{coll}.{group}().{getter}(); }}")
                j["fieldRedirects"].setdefault(owner, {})["getstatic " + fld + ":" + tdesc] = ["foxgrade/shim/BlocksCompat", meth, "()" + tdesc]
(MOD / "src/main/java/foxgrade/shim/BlocksCompat.java").write_text("package foxgrade.shim;\n\nimport net.minecraft.world.item.Item;\nimport net.minecraft.world.item.Items;\nimport net.minecraft.world.level.block.Block;\nimport net.minecraft.world.level.block.Blocks;\n\n/** The per-colour block and item constants 26.2 folded into DYED_* colour collections. Generated. */\npublic final class BlocksCompat {\n  private BlocksCompat() { }\n" + "\n".join(lines) + "\n}\n")
# ======================= WORLD RENDERING (26.2 submit API) =======================
RT = "Lnet/minecraft/client/renderer/rendertype/RenderType;"; RTO = "net/minecraft/client/renderer/rendertype/RenderType"; RTS = "net/minecraft/client/renderer/rendertype/RenderTypes"
MBS = "Lnet/minecraft/client/renderer/MultiBufferSource;"; PS = "Lcom/mojang/blaze3d/vertex/PoseStack;"; VC = "Lcom/mojang/blaze3d/vertex/VertexConsumer;"
ERS = "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"; LRS = "Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;"
SNC = "Lnet/minecraft/client/renderer/SubmitNodeCollector;"; CRS = "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
ENT = "Lnet/minecraft/world/entity/Entity;"; LIV = "Lnet/minecraft/world/entity/LivingEntity;"; MOB = "Lnet/minecraft/world/entity/Mob;"
BE = "Lnet/minecraft/world/level/block/entity/BlockEntity;"; BERS = "Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;"
V3 = "Lnet/minecraft/world/phys/Vec3;"; CRUMB = "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;"; IDF = "Lnet/minecraft/resources/Identifier;"
ERC = "foxgrade/shim/EntityRenderCompat"; BRC = "foxgrade/shim/BlockEntityRenderCompat"; MP = "Lnet/minecraft/client/model/geom/ModelPart;"
# RenderType statics: same name on RenderTypes, else the compat's substitute
rts_have = set(new[RTS]["m"]); compat_have = {"solid()", "cutout()", "cutoutMipped()", "translucent()", "lineStrip()", "debugLineStrip(D)", "entityCutoutNoCull(" + IDF + ")", "entityCutoutNoCull(" + IDF + "Z)",
    "entityTranslucentCull(" + IDF + ")", "entityNoOutline(" + IDF + ")", "entitySmoothCutout(" + IDF + ")", "eyes(" + IDF + ")", "beaconBeam(" + IDF + "Z)", "entityGlint()", "glint()", "textIntensity(" + IDF + ")", "textIntensitySeeThrough(" + IDF + ")"}
nrt = 0
for m in old["net.minecraft.client.renderer.RenderType"]:
    if not m.endswith(")" + RT) or m.startswith("lambda$"): continue
    name, rest = m.split("(", 1); key = name + "(" + ren_desc(rest.split(")")[0]) + ")" + RT
    if key in set(new[RTO]["m"]): continue
    if key in rts_have: cr.setdefault(RTO, {})[key] = [RTS, name, key[len(name):]]; nrt += 1
    elif key[:-len(RT)] in compat_have: cr.setdefault(RTO, {})[key] = ["foxgrade/shim/RenderTypeCompat", name, key[len(name):]]; nrt += 1
# LevelRenderer statics, LightTexture, ItemRenderer access
LRC = "foxgrade/shim/LevelRendererCompat"; AABB = "Lnet/minecraft/world/phys/AABB;"; VS = "Lnet/minecraft/world/phys/shapes/VoxelShape;"; BTG = "Lnet/minecraft/world/level/BlockAndTintGetter;"; BP = "Lnet/minecraft/core/BlockPos;"; BS = "Lnet/minecraft/world/level/block/state/BlockState;"
for k in ["renderLineBox(" + PS + VC + AABB + "FFFF)V", "renderLineBox(" + VC + "DDDDDDFFFF)V", "renderLineBox(" + PS + VC + "DDDDDDFFFF)V", "renderLineBox(" + PS + VC + "DDDDDDFFFFFFF)V",
          "renderShape(" + PS + VC + VS + "DDDFFFF)V", "renderVoxelShape(" + PS + VC + VS + "DDDFFFFZ)V", "getLightColor(" + BTG + BP + ")I", "getLightColor(" + BTG + BS + BP + ")I"]:
    cr.setdefault("net/minecraft/client/renderer/LevelRenderer", {})[k] = [LRC, k.split("(")[0], k[k.index("("):]]
LCU = "net/minecraft/util/LightCoordsUtil"
cr.setdefault("net/minecraft/client/renderer/LightTexture", {}).update({"pack(II)I": [LCU, "pack", "(II)I"], "block(I)I": [LCU, "block", "(I)I"], "sky(I)I": [LCU, "sky", "(I)I"]})
j["fieldRedirects"].setdefault("net/minecraft/client/renderer/LightTexture", {})["getstatic FULL_BRIGHT:I"] = ["foxgrade/shim/LightTextureCompat", "fullBright", "()I"]
cr.setdefault("net/minecraft/client/Minecraft", {})["getItemRenderer()Lnet/minecraft/client/renderer/entity/ItemRenderer;"] = ["net/minecraft/client/renderer/entity/ItemRenderer", "get", "()Lnet/minecraft/client/renderer/entity/ItemRenderer;"]
# --- entity renderers ---
SUBMIT = "submit(" + ERS + PS + SNC + CRS + ")V"
for first in (ENT, LIV, MOB):
    j["overrideAdapters"].append({"oldName": "render", "oldDesc": "(" + first + "FF" + PS + MBS + "I)V", "newName": "submit", "newDesc": "(" + ERS + PS + SNC + CRS + ")V",
        "unpack": [["static", ERC, "entity", "(" + ERS + ")" + ENT, "p1"], ["static", ERC, "yaw", "(" + ERS + ")F", "p1"], ["static", ERC, "partial", "(" + ERS + ")F", "p1"], "p2",
                   ["static", ERC, "begin", "(Ljava/lang/Object;" + ERS + PS + SNC + CRS + ")" + MBS, "this", "p1", "p2", "p3", "p4"], ["static", ERC, "light", "(" + ERS + ")I", "p1"]],
        "after": [["static", ERC, "end", "(" + ERS + ")V", "p1"]]})
    j["overrideAdapters"].append({"oldName": "getTextureLocation", "oldDesc": "(" + first + ")" + IDF, "newName": "getTextureLocation", "newDesc": "(" + LRS + ")" + IDF,
        "unpack": [["static", ERC, "entity", "(" + ERS + ")" + ENT, "p1"]]})
j.setdefault("superHooks", []).append({"name": "extractRenderState", "desc": "(" + ENT + ERS + "F)V", "hooks": [["static", ERC, "remember", "(Ljava/lang/Object;" + ENT + ERS + "F)V", "this", "p1", "p2", "p3"]]})
j.setdefault("synthesizeIfMissing", []).append({"name": "createRenderState", "desc": "()" + ERS, "call": ["static", ERC, "newState", "(Ljava/lang/Object;)" + ERS, "this"]})
for o in ["net/minecraft/client/renderer/entity/EntityRenderer", "net/minecraft/client/renderer/entity/LivingEntityRenderer", "net/minecraft/client/renderer/entity/MobRenderer"]:
    for first in (ENT, LIV, MOB):
        j["callAdapters"].setdefault(o, {})["render(" + first + "FF" + PS + MBS + "I)V"] = {"newName": "submit", "newDesc": "(" + ERS + PS + SNC + CRS + ")V",
            "args": [["static", ERC, "state", "(" + ENT + ")" + ERS, "o1"], "o4", ["static", ERC, "collector", "(" + ENT + ")" + SNC, "o1"], ["static", ERC, "camera", "(" + ENT + ")" + CRS, "o1"]]}
# --- block entity renderers ---
j["overrideAdapters"].append({"oldName": "render", "oldDesc": "(" + BE + "F" + PS + MBS + "II)V", "newName": "submit", "newDesc": "(" + BERS + PS + SNC + CRS + ")V",
    "unpack": [["static", BRC, "blockEntity", "(" + BERS + ")" + BE, "p1"], ["static", BRC, "partial", "(" + BERS + ")F", "p1"], "p2",
               ["static", BRC, "begin", "(Ljava/lang/Object;" + BERS + PS + SNC + CRS + ")" + MBS, "this", "p1", "p2", "p3", "p4"], ["static", BRC, "light", "(" + BERS + ")I", "p1"], ["static", BRC, "overlay", "()I"]],
    "after": [["static", BRC, "end", "(" + BERS + ")V", "p1"]]})
j["superHooks"].append({"name": "extractRenderState", "desc": "(" + BE + BERS + "F" + V3 + CRUMB + ")V", "hooks": [["static", BRC, "remember", "(Ljava/lang/Object;" + BE + BERS + "F" + V3 + CRUMB + ")V", "this", "p1", "p2", "p3", "p4", "p5"]]})
j["synthesizeIfMissing"].append({"name": "createRenderState", "desc": "()" + BERS, "call": ["static", BRC, "newState", "(Ljava/lang/Object;)" + BERS, "this"]})
# --- models ---
FN = "Ljava/util/function/Function;"
for owner in ["net/minecraft/client/model/EntityModel", "net/minecraft/client/model/HierarchicalModel", "net/minecraft/client/model/Model"]:
    j["ctorAdapters"].setdefault(owner, {}).update({
        "()V": {"newDesc": "(" + MP + ")V", "transforms": [{"slot": -1, "via": ["p1", "", ""]}, {"slot": -1, "via": ["foxgrade/shim/ModelCompat", "emptyRoot", "()" + MP]}]},
        "(" + FN + ")V": {"newDesc": "(" + MP + FN + ")V", "transforms": [{"slot": -1, "via": ["p1", "", ""]}, {"slot": -1, "via": ["foxgrade/shim/ModelCompat", "emptyRoot", "()" + MP]}]}})
j["overrideAdapters"].append({"oldName": "setupAnim", "oldDesc": "(" + ENT + "FFFFF)V", "newName": "setupAnim", "newDesc": "(" + ERS + ")V",
    "unpack": [["static", ERC, "entity", "(" + ERS + ")" + ENT, "p1"], ["field", LRS[1:-1], "walkAnimationPos", "F"], ["field", LRS[1:-1], "walkAnimationSpeed", "F"],
               ["field", ERS[1:-1], "ageInTicks", "F"], ["field", LRS[1:-1], "yRot", "F"], ["field", LRS[1:-1], "xRot", "F"]]})
j["classRenames"].update({"net/fabricmc/fabric/api/client/rendering/v1/EntityModelLayerRegistry": "net/fabricmc/fabric/api/client/rendering/v1/ModelLayerRegistry",
    "net/fabricmc/fabric/api/client/rendering/v1/EntityModelLayerRegistry$TexturedModelDataProvider": "net/fabricmc/fabric/api/client/rendering/v1/ModelLayerRegistry$TexturedLayerDefinitionProvider"})
j["samRenames"] = {"net/fabricmc/fabric/api/client/rendering/v1/ModelLayerRegistry$TexturedLayerDefinitionProvider": {"createModelData": "createLayerDefinition"}}
j["renames"].setdefault("net/fabricmc/fabric/api/client/rendering/v1/ModelLayerRegistry$TexturedLayerDefinitionProvider", {})["createModelData"] = "createLayerDefinition"
LC = "net/fabricmc/fabric/api/event/lifecycle/v1/"; LCC = "net/fabricmc/fabric/api/client/event/lifecycle/v1/"
for old_n, new_n, inners in [("ServerWorldEvents", "ServerLevelEvents", ["Load", "Unload"]), ("ServerChunkEvents", "ServerChunkEvents", []), ("ServerEntityEvents", "ServerEntityEvents", [])]:
    if old_n != new_n:
        j["classRenames"][LC + old_n] = LC + new_n
        for i in inners: j["classRenames"][LC + old_n + "$" + i] = LC + new_n + "$" + i
for old_n, new_n, inners in [("ClientWorldEvents", "ClientLevelEvents", [])]:
    j["classRenames"][LCC + old_n] = LCC + new_n
    for i in inners: j["classRenames"][LCC + old_n + "$" + i] = LCC + new_n + "$" + i
j["classRenames"][LCC + "ClientWorldEvents$AfterClientWorldChange"] = LCC + "ClientLevelEvents$AfterClientLevelChange"
CTE = "net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents"; STE = "net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents"
j["fieldRedirects"].setdefault(CTE, {}).update({"getstatic START_WORLD_TICK:" + EV: [FEC, "clientStartWorldTick", "()" + EV], "getstatic END_WORLD_TICK:" + EV: [FEC, "clientEndWorldTick", "()" + EV]})
j["fieldRedirects"].setdefault(STE, {}).update({"getstatic START_WORLD_TICK:" + EV: [FEC, "serverStartWorldTick", "()" + EV], "getstatic END_WORLD_TICK:" + EV: [FEC, "serverEndWorldTick", "()" + EV]})
for old_n, new_n in [("StartWorldTick", "StartLevelTick"), ("EndWorldTick", "EndLevelTick")]:
    j["classRenames"][CTE + "$" + old_n] = CTE + "$" + new_n; j["classRenames"][STE + "$" + old_n] = STE + "$" + new_n
j["samRenames"].setdefault(CTE + "$StartLevelTick", {})["onStartTick"] = "onStartTick"
j["fieldRedirects"].setdefault("net/minecraft/world/effect/MobEffects", {})["getstatic CONFUSION:Lnet/minecraft/core/Holder;"] = ["foxgrade/shim/EffectsCompat", "confusion", "()Lnet/minecraft/core/Holder;"]
ETB = "Lnet/minecraft/world/entity/EntityType$Builder;"; ET = "Lnet/minecraft/world/entity/EntityType;"
cr.setdefault("net/minecraft/world/entity/EntityType$Builder", {})["build(Ljava/lang/String;)" + ET] = ["foxgrade/shim/EntityTypeCompat", "build", "(" + ETB + "Ljava/lang/String;)" + ET]
j["renames"].setdefault("net/minecraft/client/Camera", {}).update({"getXRot": "xRot", "getYRot": "yRot", "getPosition": "position"})
j["descWidenings"].setdefault("net/minecraft/world/level/Level", {})["playSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"] = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"
j["classRenames"]["net/minecraft/world/level/block/state/properties/DirectionProperty"] = "net/minecraft/world/level/block/state/properties/EnumProperty"
RB = "Lnet/minecraft/client/renderer/RenderBuffers;"; BSRC = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;"
cr.setdefault("net/minecraft/client/Minecraft", {})["renderBuffers()" + RB] = ["foxgrade/shim/FrameCompat", "renderBuffers", "(Lnet/minecraft/client/Minecraft;)" + RB]
cr.setdefault("net/minecraft/client/renderer/RenderBuffers", {})["bufferSource()" + BSRC] = ["foxgrade/shim/FrameCompat", "bufferSource", "(" + RB + ")" + BSRC]
FONT = "Lnet/minecraft/client/gui/Font;"; DM = "Lnet/minecraft/client/gui/Font$DisplayMode;"; M4 = "Lorg/joml/Matrix4f;"; FCS = "Lnet/minecraft/util/FormattedCharSequence;"; CMP = "Lnet/minecraft/network/chat/Component;"
for t, extra in [("Ljava/lang/String;", ""), ("Ljava/lang/String;", "Z"), (CMP, ""), (FCS, "")]:
    key = "drawInBatch(" + t + "FFIZ" + M4 + MBS + DM + "II" + extra + ")I"
    cr.setdefault("net/minecraft/client/gui/Font", {})[key] = ["foxgrade/shim/FrameCompat", "drawInBatch", "(" + FONT + t + "FFIZ" + M4 + MBS + DM + "II" + extra + ")I"]
# ---- class moves the tables missed: a 1.21.1 class absent from 26.2 whose simple name exists in
# exactly one other place is a package move (Slime → monster/cubemob/Slime, GameRules → level/gamerules).
by_simple = {}
for k in new:
    if "$" in k: continue
    by_simple.setdefault(k.rsplit("/", 1)[-1], []).append(k)
moves = 0
for oc in old:
    if "$" in oc: continue
    slash = oc.replace(".", "/")
    if not slash.startswith("net/minecraft/") or slash in new or slash in REN: continue
    cands = by_simple.get(slash.rsplit("/", 1)[-1], [])
    if len(cands) == 1:
        j["classRenames"][slash] = cands[0]; moves += 1
        # inner classes move with their outer
        for k in new:
            if k.startswith(cands[0] + "$"): j["classRenames"].setdefault(slash + k[len(cands[0]):], k)
print(f"class moves by unique simple name: {moves}")
# ======================= ENTITY / ITEM API (1.21.2 – 26.x rewrite) =======================
SL = "Lnet/minecraft/server/level/ServerLevel;"; VO = "Lnet/minecraft/world/level/storage/ValueOutput;"; VI = "Lnet/minecraft/world/level/storage/ValueInput;"
DS = "Lnet/minecraft/world/damagesource/DamageSource;"; IE = "Lnet/minecraft/world/entity/item/ItemEntity;"; NB = "foxgrade/shim/NbtBridge"; EAC = "foxgrade/shim/EntityApiCompat"
IRH = "Lnet/minecraft/world/InteractionResultHolder;"; LVL = "Lnet/minecraft/world/level/Level;"; PL = "Lnet/minecraft/world/entity/player/Player;"; IH = "Lnet/minecraft/world/InteractionHand;"
ER = "Lnet/minecraft/world/entity/EntityReference;"; UU = "Ljava/util/UUID;"; NM = "Lnet/minecraft/world/entity/NeutralMob;"
SLV = ["static", EAC, "serverLevel", "(Ljava/lang/Object;)" + SL, "this"]
# overrides: the mod implements the 1.21.x signature; synthesise the 26.2 one
j["overrideAdapters"] += [
    {"oldName": "addAdditionalSaveData", "oldDesc": "(" + CT + ")V", "newName": "addAdditionalSaveData", "newDesc": "(" + VO + ")V",
     "unpack": [["static", NB, "tagForOutput", "(" + VO + ")" + CT, "p1"]], "after": [["static", NB, "flushOutput", "(" + VO + ")V", "p1"]]},
    {"oldName": "readAdditionalSaveData", "oldDesc": "(" + CT + ")V", "newName": "readAdditionalSaveData", "newDesc": "(" + VI + ")V",
     "unpack": [["static", NB, "tagOfInput", "(" + VI + ")" + CT, "p1"]]},
    {"oldName": "customServerAiStep", "oldDesc": "()V", "newName": "customServerAiStep", "newDesc": "(" + SL + ")V", "unpack": []},
    {"oldName": "doHurtTarget", "oldDesc": "(" + ENT + ")Z", "newName": "doHurtTarget", "newDesc": "(" + SL + ENT + ")Z", "unpack": ["p2"]},
    {"oldName": "hurt", "oldDesc": "(" + DS + "F)Z", "newName": "hurtServer", "newDesc": "(" + SL + DS + "F)Z", "unpack": ["p2", "p3"]},
    {"oldName": "pickUpItem", "oldDesc": "(" + IE + ")V", "newName": "pickUpItem", "newDesc": "(" + SL + IE + ")V", "unpack": ["p2"]},
    {"oldName": "actuallyHurt", "oldDesc": "(" + DS + "F)V", "newName": "actuallyHurt", "newDesc": "(" + SL + DS + "F)V", "unpack": ["p2", "p3"]},
    {"oldName": "use", "oldDesc": "(" + LVL + PL + IH + ")" + IRH, "newName": "use", "newDesc": "(" + LVL + PL + IH + ")" + IR,
     "unpack": ["p1", "p2", "p3"], "convert": ["static", "foxgrade/shim/InteractionCompat", "fromHolder", "(" + IRH + ")" + IR]},
    {"oldName": "getOwnerUUID", "oldDesc": "()" + UU, "newName": "getOwnerReference", "newDesc": "()" + ER, "unpack": [], "convert": ["static", EAC, "ownerReference", "(" + UU + ")" + ER]},
]
# super-calls into the game from those overrides
for o in ["net/minecraft/world/entity/Entity", "net/minecraft/world/entity/LivingEntity", "net/minecraft/world/entity/Mob", "net/minecraft/world/entity/PathfinderMob", "net/minecraft/world/entity/AgeableMob",
          "net/minecraft/world/entity/animal/Animal", "net/minecraft/world/entity/TamableAnimal", "net/minecraft/world/entity/animal/AbstractGolem", "net/minecraft/world/entity/monster/Monster",
          "net/minecraft/world/entity/animal/AbstractFish", "net/minecraft/world/entity/animal/AbstractSchoolingFish", "net/minecraft/world/entity/animal/WaterAnimal", "net/minecraft/world/entity/animal/AbstractCow",
          "net/minecraft/world/entity/animal/AbstractHorse", "net/minecraft/world/entity/animal/AbstractChestedHorse", "net/minecraft/world/entity/FlyingMob", "net/minecraft/world/entity/ambient/AmbientCreature",
          "net/minecraft/world/entity/animal/ShoulderRidingEntity", "net/minecraft/world/entity/projectile/Projectile", "net/minecraft/world/entity/projectile/ThrowableProjectile", "net/minecraft/world/entity/projectile/ThrowableItemProjectile",
          "net/minecraft/world/entity/projectile/AbstractArrow", "net/minecraft/world/entity/vehicle/VehicleEntity", "net/minecraft/world/entity/monster/Zombie", "net/minecraft/world/entity/monster/Skeleton", "net/minecraft/world/entity/animal/Chicken",
          "net/minecraft/world/entity/animal/Cow", "net/minecraft/world/entity/animal/Pig", "net/minecraft/world/entity/animal/Sheep", "net/minecraft/world/entity/animal/Wolf", "net/minecraft/world/entity/animal/Cat", "net/minecraft/world/entity/animal/Fox"]:
    if o not in new: continue
    j["callAdapters"].setdefault(o, {}).update({
        "addAdditionalSaveData(" + CT + ")V": {"newName": "addAdditionalSaveData", "newDesc": "(" + VO + ")V", "args": [["static", NB, "outputFor", "(" + CT + ")" + VO, "o1"]]},
        "readAdditionalSaveData(" + CT + ")V": {"newName": "readAdditionalSaveData", "newDesc": "(" + VI + ")V", "args": [["static", NB, "inputFor", "(" + CT + ")" + VI, "o1"]]},
        "customServerAiStep()V": {"newName": "customServerAiStep", "newDesc": "(" + SL + ")V", "args": [SLV]},
        "doHurtTarget(" + ENT + ")Z": {"newName": "doHurtTarget", "newDesc": "(" + SL + ENT + ")Z", "args": [SLV, "o1"]},
        "hurt(" + DS + "F)Z": {"newName": "hurtServer", "newDesc": "(" + SL + DS + "F)Z", "args": [SLV, "o1", "o2"]},
        "pickUpItem(" + IE + ")V": {"newName": "pickUpItem", "newDesc": "(" + SL + IE + ")V", "args": [SLV, "o1"]},
        "actuallyHurt(" + DS + "F)V": {"newName": "actuallyHurt", "newDesc": "(" + SL + DS + "F)V", "args": [SLV, "o1", "o2"]}})
# renames with a same-shape twin
j["renames"].setdefault("net/minecraft/world/entity/Entity", {}).update({"moveTo": "snapTo", "absMoveTo": "absSnapTo"})
j["inheritedRenames"].update({"moveTo(DDDFF)V": "snapTo", "moveTo(DDD)V": "snapTo", "absMoveTo(DDDFF)V": "absSnapTo"})
j["classRenames"].update({"net/minecraft/world/entity/MobSpawnType": "net/minecraft/world/entity/EntitySpawnReason", "net/minecraft/world/item/ArmorItem$Type": "net/minecraft/world/item/equipment/ArmorType"})
BLG = "Lnet/minecraft/world/level/BlockAndLightGetter;"; BTGc = "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
j["descWidenings"].setdefault("net/minecraft/world/entity/animal/Animal", {})["isBrightEnoughToSpawn(" + BTGc + BP + ")Z"] = "(" + BLG + BP + ")Z"
j["descWidenings"].setdefault("net/minecraft/world/level/Level", {}).update({
    "playSound(" + PL + "DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V": "(" + ENT + "DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V",
    "playSound(" + PL + ENT + "Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V": "(" + ENT + ENT + "Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"})
LE = "Lnet/minecraft/world/entity/LivingEntity;"; OE = "Lnet/minecraft/world/entity/OwnableEntity;"; TA = "Lnet/minecraft/world/entity/TamableAnimal;"
cr.setdefault("net/minecraft/world/entity/LivingEntity", {})["knockback(DDD)V"] = [EAC, "knockback", "(" + LE + "DDD)V"]
for o in ["net/minecraft/world/entity/OwnableEntity", "net/minecraft/world/entity/TamableAnimal", "net/minecraft/world/entity/animal/AbstractHorse"]:
    cr.setdefault(o, {})["getOwnerUUID()" + UU] = [EAC, "getOwnerUUID", "(" + OE + ")" + UU]
cr.setdefault("net/minecraft/world/entity/TamableAnimal", {})["setOwnerUUID(" + UU + ")V"] = [EAC, "setOwnerUUID", "(" + TA + UU + ")V"]
cr.setdefault("net/minecraft/world/entity/NeutralMob", {}).update({"addPersistentAngerSaveData(" + CT + ")V": [EAC, "addPersistentAngerSaveData", "(" + NM + CT + ")V"],
    "readPersistentAngerSaveData(" + LVL + CT + ")V": [EAC, "readPersistentAngerSaveData", "(" + NM + LVL + CT + ")V"]})
cr["net/minecraft/nbt/CompoundTag"].update({"getUUID(" + S + ")" + UU: [NB.replace("NbtBridge", "NbtCompat"), "getUUID", "(" + CT + S + ")" + UU], "hasUUID(" + S + ")Z": [NB.replace("NbtBridge", "NbtCompat"), "hasUUID", "(" + CT + S + ")Z"], "putUUID(" + S + UU + ")V": [NB.replace("NbtBridge", "NbtCompat"), "putUUID", "(" + CT + S + UU + ")V"]})
cr.setdefault("net/minecraft/world/InteractionResult", {})["sidedSuccess(Z)" + IR] = ["foxgrade/shim/InteractionCompat", "sidedSuccess", "(Z)" + IR]

# ---- constants that became Holders (SoundEvents.PIG_STEP: SoundEvent → Holder.Reference): read the
# field with its new type and unwrap, keyed on the old static-field shape.
oldF = {}; curc = None
for ln in open(pathlib.Path.home() / "foxgrade-work/mappings-1.21.1-client.txt"):
    if not ln.startswith("    ") and " -> " in ln: curc = ln.split(" -> ")[0].strip(); oldF[curc] = {}
    elif curc and "(" not in ln and " -> " in ln:
        parts = ln.strip().split(" -> ")[0].split(" ")
        if len(parts) == 2: oldF[curc][parts[1]] = jdesc(parts[0])
HOLDERS = {"Lnet/minecraft/core/Holder;", "Lnet/minecraft/core/Holder$Reference;"}
nholder = 0
for oc, flds in oldF.items():
    slash = oc.replace(".", "/"); nc = j["classRenames"].get(slash, REN.get(slash, slash))
    if nc not in new: continue
    newf = {x.split(":", 1)[0]: x.split(":", 1)[1] for x in new[nc].get("f", []) if ":" in x}
    for fname, odesc in flds.items():
        nd = newf.get(fname)
        if nd in HOLDERS and odesc.startswith("L") and odesc not in HOLDERS:
            j.setdefault("fieldRedirects", {}).setdefault(nc, {})["getstatic " + fname + ":" + odesc] = ["holder", nd, "foxgrade/shim/HolderCompat", "value", "(Lnet/minecraft/core/Holder;)Ljava/lang/Object;", odesc[1:-1]]
            nholder += 1
print(f"holder-wrapped constants bridged: {nholder}")
# ---- batch 2: the deltas the creature mods (naturalist, friends-and-foes) hit after batch 1
ELC = "foxgrade/shim/EntityLegacyCompat"; LC = "foxgrade/shim/LevelCompat"; GOC = "foxgrade/shim/GoalCompat"; ITC = "foxgrade/shim/ItemCompat"
ERS = "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"; BS2 = "Lnet/minecraft/world/level/block/state/BlockState;"
DIRN = "Lnet/minecraft/core/Direction;"; LA = "Lnet/minecraft/world/level/LevelAccessor;"; LR = "Lnet/minecraft/world/level/LevelReader;"
STA = "Lnet/minecraft/world/level/ScheduledTickAccess;"; RS = "Lnet/minecraft/util/RandomSource;"; BHR = "Lnet/minecraft/world/phys/BlockHitResult;"
IIR = "Lnet/minecraft/world/ItemInteractionResult;"; ISK = "Lnet/minecraft/world/item/ItemStack;"; IL = "Lnet/minecraft/world/level/ItemLike;"
SE = "Lnet/minecraft/sounds/SoundEvent;"; MOB = "Lnet/minecraft/world/entity/Mob;"; PN = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;"
TC = "Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"; TCS = "Lnet/minecraft/world/entity/ai/targeting/TargetingConditions$Selector;"
PRED = "Ljava/util/function/Predicate;"; TK = "Lnet/minecraft/tags/TagKey;"; PF = "Lnet/minecraft/util/profiling/ProfilerFiller;"
ITEM = "Lnet/minecraft/world/item/Item;"; PT = "Lnet/minecraft/core/particles/ParticleType;"; CLS = "Ljava/lang/Class;"
# Entity members that lost a same-shape twin: static shims take the receiver as arg 0 (lookup walks the chain)
cr.setdefault("net/minecraft/world/entity/Entity", {}).update({
    "spawnAtLocation(" + IL + ")" + IE: [ELC, "spawnAtLocation", "(" + ENT + IL + ")" + IE],
    "spawnAtLocation(" + ISK + ")" + IE: [ELC, "spawnAtLocation", "(" + ENT + ISK + ")" + IE],
    "spawnAtLocation(" + ISK + "F)" + IE: [ELC, "spawnAtLocation", "(" + ENT + ISK + "F)" + IE],
    "isInvulnerableTo(" + DS + ")Z": [ELC, "isInvulnerableTo", "(" + ENT + DS + ")Z"],
    "isInWaterOrBubble()Z": [ELC, "isInWaterOrBubble", "(" + ENT + ")Z"],
    "isControlledByLocalInstance()Z": [ELC, "isControlledByLocalInstance", "(" + ENT + ")Z"],
    "tryCheckInsideBlocks()V": [ELC, "tryCheckInsideBlocks", "(" + ENT + ")V"],
    "killedEntity(" + SL + LE + ")Z": [ELC, "killedEntity", "(" + ENT + SL + LE + ")Z"]})
cr.setdefault("net/minecraft/world/entity/Mob", {})["wantsToPickUp(" + ISK + ")Z"] = [ELC, "wantsToPickUp", "(" + MOB + ISK + ")Z"]
cr.setdefault("net/minecraft/world/entity/LivingEntity", {})["getEatingSound(" + ISK + ")" + SE] = [ELC, "getEatingSound", "(" + LE + ISK + ")" + SE]
fr = j.setdefault("fieldRedirects", {})
fr.setdefault("net/minecraft/world/entity/Entity", {}).update({
    "get walkDist:F": [ELC, "walkDist", "(" + ENT + ")F"], "put walkDist:F": [ELC, "setWalkDist", "(" + ENT + "F)V"],
    "get walkDistO:F": [ELC, "walkDistO", "(" + ENT + ")F"], "put walkDistO:F": [ELC, "setWalkDistO", "(" + ENT + "F)V"],
    "get hasImpulse:Z": [ELC, "hasImpulse", "(" + ENT + ")Z"], "put hasImpulse:Z": [ELC, "setHasImpulse", "(" + ENT + "Z)V"]})
fr.setdefault("net/minecraft/world/entity/Mob", {}).update({
    "get handDropChances:[F": [ELC, "handDropChances", "(" + MOB + ")[F"], "put handDropChances:[F": [ELC, "setHandDropChances", "(" + MOB + "[F)V"]})
# Level / Mth / navigation / targeting / items
j["renames"].setdefault("net/minecraft/world/level/Level", {}).update({"isDay": "isBrightOutside", "isNight": "isDarkOutside"})
cr.setdefault("net/minecraft/world/level/Level", {}).update({"getTimeOfDay(F)F": [LC, "getTimeOfDay", "(" + LVL + "F)F"], "getProfiler()" + PF: [LC, "getProfiler", "(" + LVL + ")" + PF]})
cr.setdefault("net/minecraft/util/Mth", {}).update({"cos(F)F": ["foxgrade/shim/MathCompat", "cos", "(F)F"], "sin(F)F": ["foxgrade/shim/MathCompat", "sin", "(F)F"]})
cr.setdefault("net/minecraft/world/entity/ai/navigation/PathNavigation", {})["setCanPassDoors(Z)V"] = [ELC, "setCanPassDoors", "(" + PN + "Z)V"]
cr.setdefault("net/minecraft/world/entity/ai/targeting/TargetingConditions", {})["selector(" + PRED + ")" + TC] = [GOC, "selector", "(" + TC + PRED + ")" + TC]
cr.setdefault("net/minecraft/world/item/ItemStack", {})["is(" + TK + ")Z"] = [ITC, "is", "(" + ISK + TK + ")Z"]
for o in ["net/minecraft/world/entity/animal/Sheep", "net/minecraft/world/entity/animal/sheep/Sheep"]:
    j["renames"].setdefault(o, {})["getDyeColor"] = "getColor"
j["ctorAdapters"].setdefault("net/minecraft/world/entity/ai/goal/target/NearestAttackableTargetGoal", {}).update({
    "(" + MOB + CLS + "IZZ" + PRED + ")V": {"newDesc": "(" + MOB + CLS + "IZZ" + TCS + ")V", "transforms": [{"slot": 5, "via": [GOC, "selector", "(" + PRED + ")" + TCS]}]},
    "(" + MOB + CLS + "Z" + PRED + ")V": {"newDesc": "(" + MOB + CLS + "Z" + TCS + ")V", "transforms": [{"slot": 3, "via": [GOC, "selector", "(" + PRED + ")" + TCS]}]}})
j["ctorAdapters"].setdefault("net/minecraft/core/particles/ItemParticleOption", {})["(" + PT + ISK + ")V"] = {"newDesc": "(" + PT + ITEM + ")V", "transforms": [{"slot": 1, "via": [ITC, "itemOf", "(" + ISK + ")" + ITEM]}]}
# overrides whose 26.2 shape changed
US_OLD = "(" + BS2 + DIRN + BS2 + LA + BP + BP + ")" + BS2; US_NEW = "(" + BS2 + LR + STA + BP + DIRN + BP + BS2 + RS + ")" + BS2
FO_OLD = "(" + LVL + BS2 + BP + ENT + "F)V"; FO_NEW = "(" + LVL + BS2 + BP + ENT + "D)V"
UI = ISK + BS2 + LVL + BP + PL + IH + BHR
j["overrideAdapters"] += [
    {"oldName": "updateShape", "oldDesc": US_OLD, "newName": "updateShape", "newDesc": US_NEW, "unpack": ["p1", "p5", "p7", ["cast", "p2", "net/minecraft/world/level/LevelAccessor"], "p4", "p6"]},
    {"oldName": "fallOn", "oldDesc": FO_OLD, "newName": "fallOn", "newDesc": FO_NEW, "unpack": ["p1", "p2", "p3", "p4", ["conv", "p5", "D2F"]]},
    {"oldName": "useItemOn", "oldDesc": "(" + UI + ")" + IIR, "newName": "useItemOn", "newDesc": "(" + UI + ")" + IR, "unpack": ["p1", "p2", "p3", "p4", "p5", "p6", "p7"],
     "convert": ["static", "net/minecraft/world/ItemInteractionResult", "toInteractionResult", "(" + IIR + ")" + IR]},
    {"oldName": "checkExtraContent", "oldDesc": "(" + PL + LVL + ISK + BP + ")V", "newName": "checkExtraContent", "newDesc": "(" + LE + LVL + ISK + BP + ")V", "unpack": [["cast", "p1", "net/minecraft/world/entity/player/Player"], "p2", "p3", "p4"]},
    {"oldName": "playEmptySound", "oldDesc": "(" + PL + LA + BP + ")V", "newName": "playEmptySound", "newDesc": "(" + LE + LA + BP + ")V", "unpack": [["cast", "p1", "net/minecraft/world/entity/player/Player"], "p2", "p3"]},
    {"oldName": "getShadowRadius", "oldDesc": "(" + ENT + ")F", "newName": "getShadowRadius", "newDesc": "(" + ERS + ")F", "unpack": [["static", "foxgrade/shim/EntityRenderCompat", "entity", "(" + ERS + ")" + ENT, "p1"]]},
    {"oldName": "shouldShowName", "oldDesc": "(" + ENT + ")Z", "newName": "shouldShowName", "newDesc": "(" + ENT + "D)Z", "unpack": ["p1"]},
]
for o in ["net/minecraft/world/level/block/Block", "net/minecraft/world/level/block/state/BlockBehaviour", "net/minecraft/world/level/block/HorizontalDirectionalBlock", "net/minecraft/world/level/block/BaseEntityBlock",
          "net/minecraft/world/level/block/CropBlock", "net/minecraft/world/level/block/BushBlock", "net/minecraft/world/level/block/VegetationBlock", "net/minecraft/world/level/block/DoorBlock", "net/minecraft/world/level/block/FenceBlock",
          "net/minecraft/world/level/block/SlabBlock", "net/minecraft/world/level/block/StairBlock", "net/minecraft/world/level/block/WallBlock", "net/minecraft/world/level/block/LeavesBlock", "net/minecraft/world/level/block/FlowerBlock",
          "net/minecraft/world/level/block/SaplingBlock", "net/minecraft/world/level/block/FallingBlock", "net/minecraft/world/level/block/RotatedPillarBlock", "net/minecraft/world/level/block/DirectionalBlock", "net/minecraft/world/level/block/TrapDoorBlock"]:
    if o not in new: continue
    j["callAdapters"].setdefault(o, {}).update({
        "updateShape" + US_OLD: {"newName": "updateShape", "newDesc": US_NEW, "args": ["o1", ["cast", "o4", "net/minecraft/world/level/LevelReader"], ["cast", "o4", "net/minecraft/world/level/ScheduledTickAccess"], "o5", "o2", "o6", "o3", ["static", LC, "randomOf", "(" + LA + ")" + RS, "o4"]]},
        "fallOn" + FO_OLD: {"newName": "fallOn", "newDesc": FO_NEW, "args": ["o1", "o2", "o3", "o4", ["conv", "o5", "F2D"]]}})
j["descWidenings"].setdefault("net/minecraft/world/item/BucketItem", {}).update({
    "playEmptySound(" + PL + LA + BP + ")V": "(" + LE + LA + BP + ")V", "checkExtraContent(" + PL + LVL + ISK + BP + ")V": "(" + LE + LVL + ISK + BP + ")V"})
for old_c, new_c in {"net/minecraft/world/level/storage/loot/parameters/LootContextParam": "net/minecraft/util/context/ContextKey",
                     "net/minecraft/world/level/storage/loot/parameters/LootContextParamSet": "net/minecraft/util/context/ContextKeySet"}.items():
    if new_c in new: j["classRenames"][old_c] = new_c
print("entity/item batch 2: in")

# ---- moved constants: a static field gone from its 1.21.1 owner that now lives on <Owner>s (EntityType.FOX → EntityTypes.FOX)
nmoved = 0
for oc, flds in oldF.items():
    slash = oc.replace(".", "/"); nc = j["classRenames"].get(slash, REN.get(slash, slash))
    if nc not in new or (nc + "s") not in new: continue
    newf = {x.split(":", 1)[0] for x in new[nc].get("f", [])}
    holder = {x.split(":", 1)[0]: x.split(":", 1)[1] for x in new[nc + "s"].get("f", [])}
    for fname, odesc in flds.items():
        if fname in newf or fname not in holder or holder[fname] != odesc: continue
        j["fieldRedirects"].setdefault(nc, {}).setdefault("getstatic " + fname + ":" + odesc, ["move", nc + "s", odesc]); nmoved += 1
print(f"moved constants bridged: {nmoved}")
# ---- retyped constants: a static Codec that became a MapCodec (SpawnerData.CODEC) — read it and unwrap with .codec()
CODEC, MAPCODEC = "Lcom/mojang/serialization/Codec;", "Lcom/mojang/serialization/MapCodec;"
nretyped = 0
for oc, flds in oldF.items():
    slash = oc.replace(".", "/"); nc = j["classRenames"].get(slash, REN.get(slash, slash))
    if nc not in new: continue
    newf = {x.split(":", 1)[0]: x.split(":", 1)[1] for x in new[nc].get("f", [])}
    for fname, odesc in flds.items():
        if odesc != CODEC or newf.get(fname) != MAPCODEC: continue
        j["fieldRedirects"].setdefault(nc, {}).setdefault("getstatic " + fname + ":" + CODEC,
            ["holder", MAPCODEC, "foxgrade/shim/CodecCompat", "toCodec", "(" + MAPCODEC + ")" + CODEC, "com/mojang/serialization/Codec"]); nretyped += 1
print(f"Codec→MapCodec constants bridged: {nretyped}")
# ---- File → Path fields (SavedDataStorage.dataFolder): read with the new type and convert back
FILE, PATHD = "Ljava/io/File;", "Ljava/nio/file/Path;"
nfp = 0
for oc, flds in oldF.items():
    slash = oc.replace(".", "/"); nc = j["classRenames"].get(slash, REN.get(slash, slash))
    if nc not in new: continue
    newf = {x.split(":", 1)[0]: x.split(":", 1)[1] for x in new[nc].get("f", [])}
    for fname, odesc in flds.items():
        if odesc != FILE or newf.get(fname) != PATHD: continue
        for kind in ("get ", "getstatic "):
            j["fieldRedirects"].setdefault(nc, {}).setdefault(kind + fname + ":" + FILE, ["holder", PATHD, "foxgrade/shim/IoCompat", "toFile", "(" + PATHD + ")" + FILE, "java/io/File"])
        nfp += 1
print(f"File→Path fields bridged: {nfp}")
# ---- batch 3: what naturalist / friends-and-foes still hit after batch 2
ET = "Lnet/minecraft/world/entity/EntityType;"; ESR = "Lnet/minecraft/world/entity/EntitySpawnReason;"; HOLD = "Lnet/minecraft/core/Holder;"; PTY = "Lnet/minecraft/world/level/pathfinder/PathType;"
GR = "Lnet/minecraft/world/level/gamerules/GameRules;"; GRK = "Lnet/minecraft/world/level/GameRules$Key;"; CMP = "Lnet/minecraft/network/chat/Component;"; AABB = "Lnet/minecraft/world/phys/AABB;"
RM = "Lnet/minecraft/world/item/crafting/RecipeManager;"; RT = "Lnet/minecraft/world/item/crafting/RecipeType;"; ING = "Lnet/minecraft/world/item/crafting/Ingredient;"; ICD = "Lnet/minecraft/world/item/ItemCooldowns;"
DYE = "Lnet/minecraft/world/item/DyeItem;"; DC = "Lnet/minecraft/world/item/DyeColor;"; VEC = "Lnet/minecraft/world/phys/Vec3;"; ES = "Lnet/minecraft/world/entity/EquipmentSlot;"; V3F = "Lorg/joml/Vector3f;"
DPO = "Lnet/minecraft/core/particles/DustParticleOptions;"; ANS = "Lnet/minecraft/world/entity/AnimationState;"; MP = "Lnet/minecraft/client/model/geom/ModelPart;"; ERD = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;"
QF = "Lorg/joml/Quaternionf;"; LERS = "Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;"; PS = "Lcom/mojang/blaze3d/vertex/PoseStack;"; TTC = "Lnet/minecraft/world/item/Item$TooltipContext;"
TTD = "Lnet/minecraft/world/item/component/TooltipDisplay;"; TTF = "Lnet/minecraft/world/item/TooltipFlag;"; LST = "Ljava/util/List;"; CONS = "Ljava/util/function/Consumer;"; OPT = "Ljava/util/Optional;"; STRM = "Ljava/util/stream/Stream;"
ACT = "Lnet/minecraft/world/entity/schedule/Activity;"; IML = "Lcom/google/common/collect/ImmutableList;"; SET = "Ljava/util/Set;"; MMT = "Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;"
LPB = "Lnet/minecraft/world/level/storage/loot/LootPool$Builder;"; LIC = "Lnet/minecraft/world/level/storage/loot/predicates/LootItemCondition;"; FPB = "Lnet/minecraft/world/food/FoodProperties$Builder;"; MEI = "Lnet/minecraft/world/effect/MobEffectInstance;"
HLP = "Lnet/minecraft/core/HolderLookup$Provider;"; TAG = "Lnet/minecraft/nbt/Tag;"; NNL = "Lnet/minecraft/core/NonNullList;"; CU = "Lnet/minecraft/world/entity/ContainerUser;"; WAS = "Lnet/minecraft/world/entity/WalkAnimationState;"
CA = "Lnet/minecraft/world/level/chunk/ChunkAccess;"; PRT = "Lnet/minecraft/client/particle/ParticleRenderType;"; PLS = "Lnet/minecraft/world/entity/player/PlayerSkin;"; ID = "Lnet/minecraft/resources/Identifier;"
ANM = "Lnet/minecraft/world/entity/animal/Animal;"; PFM = "Lnet/minecraft/world/entity/PathfinderMob;"; HM = "Lnet/minecraft/client/model/HierarchicalModel;"; AD = "Lnet/minecraft/client/animation/AnimationDefinition;"
RTY = "Lnet/minecraft/client/renderer/rendertype/RenderType;"; SEI = "Lnet/minecraft/world/item/SpawnEggItem;"; IPR = "Lnet/minecraft/world/item/Item$Properties;"; TA2 = "Lnet/minecraft/world/entity/TamableAnimal;"; SEL = TCS; RS2 = "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
GRC = "foxgrade/shim/GameRulesCompat"; PC = "foxgrade/shim/PathCompat"; PAC = "foxgrade/shim/ParticleCompat"; MCC = "foxgrade/shim/MinecraftCompat"; ANC = "foxgrade/shim/AnimationCompat"; RC = "foxgrade/shim/RecipeCompat"
BAC = "foxgrade/shim/BlockApiCompat"; ERC = "foxgrade/shim/EntityRenderCompat"; EFC = "foxgrade/shim/EffectsCompat"; NC = "foxgrade/shim/NbtCompat"; SKC = "foxgrade/shim/SkinCompat"; PLC = "foxgrade/shim/PlayerCompat"; MOC = "foxgrade/shim/ModelCompat"
# static constants that moved or changed shape
fr = j["fieldRedirects"]
fr.setdefault("net/minecraft/world/entity/EntitySpawnReason", {})["getstatic SPAWN_EGG:" + ESR] = [ELC, "spawnEggReason", "()" + ESR]
fr.setdefault("net/minecraft/world/effect/MobEffects", {}).update({"getstatic HARM:" + HOLD: [EFC, "harm", "()" + HOLD], "getstatic HEAL:" + HOLD: [EFC, "heal", "()" + HOLD],
    "getstatic MOVEMENT_SPEED:" + HOLD: [EFC, "movementSpeed", "()" + HOLD], "getstatic MOVEMENT_SLOWDOWN:" + HOLD: [EFC, "movementSlowdown", "()" + HOLD]})
fr.setdefault("net/minecraft/world/level/pathfinder/PathType", {}).update({"getstatic DAMAGE_FIRE:" + PTY: [PC, "damageFire", "()" + PTY], "getstatic DANGER_FIRE:" + PTY: [PC, "dangerFire", "()" + PTY],
    "getstatic DAMAGE_OTHER:" + PTY: [PC, "damageOther", "()" + PTY], "getstatic DANGER_OTHER:" + PTY: [PC, "dangerOther", "()" + PTY], "getstatic DANGER_POWDER_SNOW:" + PTY: [PC, "dangerPowderSnow", "()" + PTY]})
fr.setdefault("net/minecraft/client/particle/ParticleRenderType", {})["getstatic PARTICLE_SHEET_TRANSLUCENT:" + PRT] = [PAC, "sheetTranslucent", "()" + PRT]
fr.setdefault("net/minecraft/world/level/gamerules/GameRules", {}).update({"getstatic RULE_MOBGRIEFING:" + GRK: [GRC, "ruleMobGriefing", "()" + GRK], "getstatic RULE_DOMOBLOOT:" + GRK: [GRC, "ruleDoMobLoot", "()" + GRK],
    "getstatic RULE_DOMOBSPAWNING:" + GRK: [GRC, "ruleDoMobSpawning", "()" + GRK], "getstatic RULE_DOTILEDROPS:" + GRK: [GRC, "ruleDoTileDrops", "()" + GRK], "getstatic RULE_DOENTITYDROPS:" + GRK: [GRC, "ruleDoEntityDrops", "()" + GRK],
    "getstatic RULE_KEEPINVENTORY:" + GRK: [GRC, "ruleKeepInventory", "()" + GRK]})
fr.setdefault("net/minecraft/world/entity/Entity", {}).update({"get fallDistance:F": [ELC, "fallDistance", "(" + ENT + ")F"], "put fallDistance:F": [ELC, "setFallDistance", "(" + ENT + "F)V"]})
fr.setdefault("net/minecraft/world/entity/player/Player", {}).update({"get bob:F": [PLC, "bob", "(" + PL + ")F"], "get oBob:F": [PLC, "oBob", "(" + PL + ")F"], "put bob:F": [PLC, "setBob", "(" + PL + "F)V"], "put oBob:F": [PLC, "setOBob", "(" + PL + "F)V"]})
for c in ["xCloak", "yCloak", "zCloak", "xCloakO", "yCloakO", "zCloakO"]:
    fr["net/minecraft/world/entity/player/Player"].update({"get " + c + ":D": [PLC, "cloak", "(" + PL + ")D"], "put " + c + ":D": [PLC, "setCloak", "(" + PL + "D)V"]})
fr.setdefault("net/minecraft/client/Minecraft", {})["getstatic ON_OSX:Z"] = [MCC, "onOsx", "()Z"]
# renames with a same-shape twin
j["renames"].setdefault("net/minecraft/core/Registry", {}).update({"getHolder": "getOptional", "getHolderOrThrow": "getOrThrow", "holders": "listElements"})
j["renames"].setdefault("net/minecraft/core/RegistryAccess", {})["registry"] = "lookup"
j["renames"].setdefault("net/minecraft/world/item/component/CustomData", {})["getUnsafe"] = "copyTag"
# widenings
j["descWidenings"].setdefault("net/minecraft/world/entity/EntityType", {})["spawn(" + SL + ISK + PL + BP + ESR + "ZZ)" + ENT] = "(" + SL + ISK + LE + BP + ESR + "ZZ)" + ENT
j["descWidenings"].setdefault("net/minecraft/world/level/Level", {}).update({"mayInteract(" + PL + BP + ")Z": "(" + ENT + BP + ")Z", "levelEvent(" + PL + "I" + BP + "I)V": "(" + ENT + "I" + BP + "I)V"})
j["descWidenings"].setdefault("net/minecraft/world/level/LevelAccessor", {}).update({"playSound(" + PL + BP + "Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V": "(" + ENT + BP + "Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V",
    "levelEvent(" + PL + "I" + BP + "I)V": "(" + ENT + "I" + BP + "I)V"})
j["descWidenings"].setdefault("net/minecraft/world/Container", {}).update({"startOpen(" + PL + ")V": "(" + CU + ")V", "stopOpen(" + PL + ")V": "(" + CU + ")V"})
j["descWidenings"].setdefault("net/minecraft/client/animation/Keyframe", {})["<init>(F" + V3F + "Lnet/minecraft/client/animation/AnimationChannel$Interpolation;)V"] = "(FLorg/joml/Vector3fc;Lnet/minecraft/client/animation/AnimationChannel$Interpolation;)V"
# constructors
j["ctorAdapters"].setdefault("net/minecraft/world/item/SpawnEggItem", {})["(" + ET + "II" + IPR + ")V"] = {"factory": [ITC, "spawnEgg", "(" + ET + "II" + IPR + ")" + SEI]}
IST = "Lnet/minecraft/world/item/ItemStack;"; ILK = "Lnet/minecraft/world/level/ItemLike;"
j["ctorAdapters"].setdefault("net/minecraft/world/item/ItemStack", {}).update({"(" + ILK + ")V": {"factory": [ITC, "stack", "(" + ILK + ")" + IST]}, "(" + ILK + "I)V": {"factory": [ITC, "stack", "(" + ILK + "I)" + IST]}})
j["ctorAdapters"].setdefault("net/minecraft/world/level/ChunkPos", {})["(J)V"] = {"factory": ["foxgrade/shim/LevelCompat", "chunkPos", "(J)Lnet/minecraft/world/level/ChunkPos;"]}
TT = "Lnet/minecraft/server/level/TicketType;"
cr.setdefault("net/minecraft/server/level/TicketType", {}).update({
    "create(Ljava/lang/String;Ljava/util/Comparator;I)" + TT: ["foxgrade/shim/LevelCompat", "ticketType", "(Ljava/lang/String;Ljava/util/Comparator;I)" + TT],
    "create(Ljava/lang/String;Ljava/util/Comparator;)" + TT: ["foxgrade/shim/LevelCompat", "ticketType", "(Ljava/lang/String;Ljava/util/Comparator;)" + TT]})
BBP = "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;"; SND = "Lnet/minecraft/sounds/SoundEvent;"; BAC_ = "foxgrade/shim/BlockApiCompat"
j["ctorAdapters"].setdefault("net/minecraft/world/level/block/LeavesBlock", {})["(" + BBP + ")V"] = {"newDesc": "(F" + BBP + ")V", "transforms": [{"slot": -1, "via": [BAC_, "leafParticleChance", "()F"]}], "factory": [BAC_, "leaves", "(" + BBP + ")Lnet/minecraft/world/level/block/LeavesBlock;"]}   # abstract in 26.2: new LeavesBlock → tinted variant; super(props) keeps the grown ctor
j["ctorAdapters"].setdefault("net/minecraft/world/level/block/ChestBlock", {})["(" + BBP + "Ljava/util/function/Supplier;)V"] = {"newDesc": "(Ljava/util/function/Supplier;" + SND + SND + BBP + ")V",
    "args": [["o2"], ["static", BAC_, "chestOpenSound", "()" + SND], ["static", BAC_, "chestCloseSound", "()" + SND], ["o1"]]}   # "oN" is 1-based
j["overrideAdapters"].append({"oldName": "element", "oldDesc": "(Lnet/minecraft/resources/Identifier;)Ljava/lang/Object;", "newName": "element", "newDesc": "(Lnet/minecraft/resources/Identifier;Z)Ljava/lang/Object;", "unpack": ["p1"]})   # TagEntry.Lookup ("pN" is 1-based)
j["ctorAdapters"].setdefault("net/minecraft/core/particles/DustParticleOptions", {})["(" + V3F + "F)V"] = {"factory": [PAC, "dust", "(" + V3F + "F)" + DPO]}
j["ctorAdapters"].setdefault("net/minecraft/world/item/alchemy/Potion", {})["([" + MEI + ")V"] = {"newDesc": "(Ljava/lang/String;[" + MEI + ")V", "transforms": [{"slot": -1, "via": [ITC, "potionName", "()Ljava/lang/String;"]}]}
TIP = "net/minecraft/world/entity/projectile/throwableitemprojectile/ThrowableItemProjectile"
j["ctorAdapters"].setdefault(TIP, {}).update({"(" + ET + "DDD" + LVL + ")V": {"newDesc": "(" + ET + "DDD" + LVL + ISK + ")V", "transforms": [{"slot": -2, "via": [ELC, "emptyStack", "()" + ISK]}]},
    "(" + ET + LE + LVL + ")V": {"newDesc": "(" + ET + LE + LVL + ISK + ")V", "transforms": [{"slot": -2, "via": [ELC, "emptyStack", "()" + ISK]}]}})
j["ctorAdapters"].setdefault("net/minecraft/world/entity/ai/goal/target/NonTameRandomTargetGoal", {})["(" + TA2 + CLS + "Z" + PRED + ")V"] = {"newDesc": "(" + TA2 + CLS + "Z" + SEL + ")V", "transforms": [{"slot": 3, "via": [GOC, "selector", "(" + PRED + ")" + SEL]}]}
# call redirects (receiver → arg 0)
cr.setdefault("net/minecraft/world/entity/LivingEntity", {}).update({"isDamageSourceBlocked(" + DS + ")Z": [ELC, "isDamageSourceBlocked", "(" + LE + DS + ")Z"], "getSlotForHand(" + IH + ")" + ES: [ELC, "getSlotForHand", "(" + IH + ")" + ES]})
cr.setdefault("net/minecraft/world/entity/PathfinderMob", {})["clearRestriction()V"] = [ELC, "clearRestriction", "(" + PFM + ")V"]
cr.setdefault("net/minecraft/world/entity/animal/Animal", {})["getAttackBoundingBox()" + AABB] = [ELC, "getAttackBoundingBox", "(" + ANM + ")" + AABB]
cr.setdefault("net/minecraft/world/entity/player/Player", {}).update({"displayClientMessage(" + CMP + "Z)V": [ELC, "displayClientMessage", "(" + PL + CMP + "Z)V"],
    "getShoulderEntityLeft()" + CT: [ELC, "shoulderEntity", "(" + PL + ")" + CT], "getShoulderEntityRight()" + CT: [ELC, "shoulderEntity", "(" + PL + ")" + CT]})
cr.setdefault("net/minecraft/world/entity/EntityType", {}).update({"create(" + LVL + ")" + ENT: [ELC, "create", "(" + ET + LVL + ")" + ENT], "byString(Ljava/lang/String;)" + OPT: [ELC, "byString", "(Ljava/lang/String;)" + OPT], "is(" + TK + ")Z": [ELC, "isType", "(" + ET + TK + ")Z"]})
cr["net/minecraft/world/entity/Entity"]["save(" + CT + ")Z"] = [ELC, "save", "(" + ENT + CT + ")Z"]
cr.setdefault("net/minecraft/world/level/Level", {}).update({"getNearestPlayer(" + TC + LE + ")" + PL: [LC, "getNearestPlayer", "(" + LVL + TC + LE + ")" + PL], "getNearbyPlayers(" + TC + LE + AABB + ")" + LST: [LC, "getNearbyPlayers", "(" + LVL + TC + LE + AABB + ")" + LST],
    "getNearbyEntities(" + CLS + TC + LE + AABB + ")" + LST: [LC, "getNearbyEntities", "(" + LVL + CLS + TC + LE + AABB + ")" + LST], "getNearestEntity(" + CLS + TC + LE + "DDD" + AABB + ")" + LE: [LC, "getNearestEntity", "(" + LVL + CLS + TC + LE + "DDD" + AABB + ")" + LE],
    "getGameRules()" + GR: [GRC, "getGameRules", "(" + LVL + ")" + GR], "getRecipeManager()" + RM: [RC, "getRecipeManager", "(" + LVL + ")" + RM]})
cr.setdefault("net/minecraft/world/level/gamerules/GameRules", {})["getBoolean(" + GRK + ")Z"] = [GRC, "getBoolean", "(" + GR + GRK + ")Z"]
cr.setdefault("net/minecraft/world/item/crafting/RecipeManager", {})["getAllRecipesFor(" + RT + ")" + LST] = [RC, "getAllRecipesFor", "(" + RM + RT + ")" + LST]
cr.setdefault("net/minecraft/world/item/crafting/Ingredient", {})["of(" + TK + ")" + ING] = [ITC, "ingredientOf", "(" + TK + ")" + ING]
cr.setdefault("net/minecraft/world/item/ItemCooldowns", {}).update({"addCooldown(" + ITEM + "I)V": [ITC, "addCooldown", "(" + ICD + ITEM + "I)V"], "isOnCooldown(" + ITEM + ")Z": [ITC, "isOnCooldown", "(" + ICD + ITEM + ")Z"]})
cr.setdefault("net/minecraft/world/item/DyeItem", {})["getDyeColor()" + DC] = [ITC, "dyeColor", "(" + DYE + ")" + DC]
cr["net/minecraft/world/item/ItemStack"].update({"save(" + HLP + ")" + TAG: [ITC, "stackSave", "(" + ISK + HLP + ")" + TAG], "parse(" + HLP + TAG + ")" + OPT: [ITC, "stackParse", "(" + HLP + TAG + ")" + OPT]})
cr.setdefault("net/minecraft/world/ContainerHelper", {}).update({"loadAllItems(" + CT + NNL + HLP + ")V": [NC, "loadAllItems", "(" + CT + NNL + HLP + ")V"], "saveAllItems(" + CT + NNL + HLP + ")" + CT: [NC, "saveAllItems", "(" + CT + NNL + HLP + ")" + CT]})
cr.setdefault("net/minecraft/client/model/geom/ModelPart", {})["getAllParts()" + STRM] = [MOC, "getAllParts", "(" + MP + ")" + STRM]
cr.setdefault("net/minecraft/client/renderer/entity/EntityRenderDispatcher", {})["cameraOrientation()" + QF] = [MCC, "cameraOrientation", "(" + ERD + ")" + QF]
cr.setdefault("net/minecraft/world/entity/AnimationState", {})["getAccumulatedTime()J"] = [ANC, "getAccumulatedTime", "(" + ANS + ")J"]
cr.setdefault("net/minecraft/client/animation/KeyframeAnimations", {})["animate(" + HM + AD + "JF" + V3F + ")V"] = [ANC, "animate", "(" + HM + AD + "JF" + V3F + ")V"]
cr.setdefault("net/minecraft/world/level/storage/loot/LootPool$Builder", {})["conditionally(" + LIC + ")" + LPB] = [RC, "conditionally", "(" + LPB + LIC + ")" + LPB]
cr.setdefault("net/minecraft/world/food/FoodProperties$Builder", {})["effect(" + MEI + "F)" + FPB] = [RC, "effect", "(" + FPB + MEI + "F)" + FPB]
cr.setdefault("net/minecraft/world/level/block/BeehiveBlock", {})["dropHoneycomb(" + LVL + BP + ")V"] = [BAC, "dropHoneycomb", "(" + LVL + BP + ")V"]
cr.setdefault("net/minecraft/world/entity/player/PlayerSkin", {})["capeTexture()" + ID] = [SKC, "capeTexture", "(" + PLS + ")" + ID]
cr.setdefault("net/minecraft/client/renderer/rendertype/RenderType", {})["entityGlintDirect()" + RTY] = ["net/minecraft/client/renderer/rendertype/RenderTypes", "entityGlint", "()" + RTY]
# Fabric API moved the pack activation enum; ModNioPackResources.create now takes the v1 one (towns-and-towers).
_MNP = "Lnet/fabricmc/fabric/impl/resource/pack/ModNioPackResources;"; _MC_ = "Lnet/fabricmc/loader/api/ModContainer;"
_PT_ = "Lnet/minecraft/server/packs/PackType;"; _RPAT = "Lnet/fabricmc/fabric/api/resource/ResourcePackActivationType;"
_oldCreate = "create(Ljava/lang/String;" + _MC_ + "Ljava/lang/String;" + _PT_ + _RPAT + "Z)" + _MNP
cr.setdefault("net/fabricmc/fabric/impl/resource/pack/ModNioPackResources", {})[_oldCreate] = ["foxgrade/shim/PackCompat", "createModPack", "(Ljava/lang/String;" + _MC_ + "Ljava/lang/String;" + _PT_ + _RPAT + "Z)" + _MNP]
# WorldVersion became a record in 26.2: every getX() lost its prefix (entityculling reads getName()).
for _g, _n, _r in [("getName", "name", "Ljava/lang/String;"), ("getId", "id", "Ljava/lang/String;"),
                   ("getBuildTime", "buildTime", "Ljava/util/Date;"), ("getProtocolVersion", "protocolVersion", "I"),
                   ("isStable", "stable", "Z")]:
    j.setdefault("renames", {}).setdefault("net/minecraft/WorldVersion", {})[_g] = _n
# Fabric API renamed the key-binding helper and moved its package (entityculling).
j["classRenames"]["net/fabricmc/fabric/api/client/keybinding/v1/KeyBindingHelper"] = "net/fabricmc/fabric/api/client/keymapping/v1/KeyMappingHelper"
_KMH = "net/fabricmc/fabric/api/client/keymapping/v1/KeyMappingHelper"; _KM = "Lnet/minecraft/client/KeyMapping;"
j.setdefault("renames", {}).setdefault(_KMH, {}).update({"registerKeyBinding": "registerKeyMapping", "getBoundKeyOf": "getBoundKeyOf"})
# ReloadableServerRegistries.Holder.get() -> lookup(), widened to HolderLookup.Provider in 26.2 (wthit).
_RSRH = "Lnet/minecraft/server/ReloadableServerRegistries$Holder;"; _FROZ = "Lnet/minecraft/core/RegistryAccess$Frozen;"
cr.setdefault("net/minecraft/server/ReloadableServerRegistries$Holder", {})["get()" + _FROZ] = ["foxgrade/shim/RegistryCompat", "reloadableRegistries", "(" + _RSRH + ")" + _FROZ]
# RenderSystem.recordRenderCall + the RenderCall interface: both gone in 26.2 (fancymenu, drippy-loading-screen).
_RCALL = "Lcom/mojang/blaze3d/pipeline/RenderCall;"
cr.setdefault("com/mojang/blaze3d/systems/RenderSystem", {})["recordRenderCall(" + _RCALL + ")V"] = ["foxgrade/shim/RenderSystemCompat", "recordRenderCall", "(" + _RCALL + ")V"]
# Quilt mods looking themselves up: the container comes back with the original id on either host (see QuiltSelfContainer).
cr.setdefault("org/quiltmc/loader/api/QuiltLoader", {}).update({"getModContainer(Ljava/lang/String;)Ljava/util/Optional;": ["foxgrade/shim/QuiltSelfContainer", "byId", "(Ljava/lang/String;)Ljava/util/Optional;"], "getModContainer(Ljava/lang/Class;)Ljava/util/Optional;": ["foxgrade/shim/QuiltSelfContainer", "byClass", "(Ljava/lang/Class;)Ljava/util/Optional;"]})
# RenderTarget under the 26.2 GPU abstraction: main target lives on the game renderer; viewport fields and GL ids are bridged.
_RTG = "Lcom/mojang/blaze3d/pipeline/RenderTarget;"; _RTC = "foxgrade/shim/RenderTargetCompat"
cr.setdefault("net/minecraft/client/Minecraft", {})["getMainRenderTarget()" + _RTG] = ["foxgrade/shim/MinecraftCompat", "getMainRenderTarget", "(Lnet/minecraft/client/Minecraft;)" + _RTG]
cr.setdefault("com/mojang/blaze3d/pipeline/RenderTarget", {}).update({"getColorTextureId()I": [_RTC, "getColorTextureId", "(" + _RTG + ")I"], "getDepthTextureId()I": [_RTC, "getDepthTextureId", "(" + _RTG + ")I"]})
j["fieldRedirects"].setdefault("com/mojang/blaze3d/pipeline/RenderTarget", {}).update({"get viewWidth:I": [_RTC, "viewWidth", "(" + _RTG + ")I"], "get viewHeight:I": [_RTC, "viewHeight", "(" + _RTG + ")I"], "get frameBufferId:I": [_RTC, "frameBufferId", "(" + _RTG + ")I"]})
# Fabric networking packet factories: the configuration-phase one was renamed, the client-to-server ones build a payload packet.
_CPP = "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;"; _PKT = "Lnet/minecraft/network/protocol/Packet;"; _NWC = "foxgrade/shim/NetworkingCompat"
j.setdefault("renames", {}).setdefault("net/fabricmc/fabric/api/networking/v1/ServerConfigurationNetworking", {})["createS2CPacket"] = "createClientboundPacket"
for _o in ["net/fabricmc/fabric/api/networking/v1/ClientPlayNetworking", "net/fabricmc/fabric/api/networking/v1/ClientConfigurationNetworking"]:
    cr.setdefault(_o, {})["createC2SPacket(" + _CPP + ")" + _PKT] = [_NWC, "createC2SPacket", "(" + _CPP + ")" + _PKT]
# BlockEntityRendererProvider.Context became a record with bare accessor names; the item renderer it no longer carries is the shim.
_BEC = "net/minecraft/client/renderer/blockentity/BlockEntityRendererProvider$Context"
j.setdefault("renames", {}).setdefault(_BEC, {}).update({"getBlockEntityRenderDispatcher": "blockEntityRenderDispatcher", "getEntityRenderer": "entityRenderer", "getModelSet": "entityModelSet", "getFont": "font"})
cr.setdefault(_BEC, {})["getItemRenderer()Lnet/minecraft/client/renderer/entity/ItemRenderer;"] = ["net/minecraft/client/renderer/entity/ItemRenderer", "get", "(Ljava/lang/Object;)Lnet/minecraft/client/renderer/entity/ItemRenderer;"]
# ---- 1.21 chunk internals (Distant Horizons): paletted containers, proto chunks, structure gen, BlockState accessors
j["classRenames"].setdefault("net/minecraft/world/level/chunk/PalettedContainer$Strategy", "net/minecraft/world/level/chunk/Strategy")
CC = "foxgrade/shim/ChunkCompat"; STR = "Lnet/minecraft/world/level/chunk/Strategy;"; IDM = "Lnet/minecraft/core/IdMap;"; CDC = "Lcom/mojang/serialization/Codec;"; BST = "Lnet/minecraft/world/level/block/state/BlockState;"
cr.setdefault("net/minecraft/world/level/chunk/PalettedContainer", {})["codecRW(" + IDM + CDC + STR + "Ljava/lang/Object;)" + CDC] = [CC, "codecRW", "(" + IDM + CDC + STR + "Ljava/lang/Object;)" + CDC]
j["ctorAdapters"].setdefault("net/minecraft/world/level/chunk/PalettedContainer", {})["(" + IDM + "Ljava/lang/Object;" + STR + ")V"] = {"newDesc": "(Ljava/lang/Object;" + STR + ")V", "args": [["o2"], ["o3"]]}
PCF = "Lnet/minecraft/world/level/chunk/PalettedContainerFactory;"; REG = "Lnet/minecraft/core/Registry;"; BLD = "Lnet/minecraft/world/level/levelgen/blending/BlendingData;"
_pc = "(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/UpgradeData;Lnet/minecraft/world/level/LevelHeightAccessor;"
j["ctorAdapters"].setdefault("net/minecraft/world/level/chunk/ProtoChunk", {})[_pc + REG + BLD + ")V"] = {"newDesc": _pc + PCF + BLD + ")V", "args": [["o1"], ["o2"], ["o3"], ["static", CC, "factory", "(" + REG + ")" + PCF, "o4"], ["o5"]]}
BSO = "net/minecraft/world/level/block/state/BlockState"
cr.setdefault(BSO, {}).update({"block()Lnet/minecraft/world/level/block/Block;": [CC, "block", "(" + BST + ")Lnet/minecraft/world/level/block/Block;"],
    "getTags()Ljava/util/stream/Stream;": [CC, "getTags", "(" + BST + ")Ljava/util/stream/Stream;"],
    "propagatesSkylightDown(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z": [CC, "propagatesSkylightDown", "(" + BST + "Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z"]})
cr.setdefault("net/minecraft/world/level/block/BeaconBeamBlock", {})["color()Lnet/minecraft/world/item/DyeColor;"] = [CC, "color", "(Lnet/minecraft/world/level/block/BeaconBeamBlock;)Lnet/minecraft/world/item/DyeColor;"]
_cs = "(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;)V"
cr.setdefault("net/minecraft/world/level/chunk/ChunkGenerator", {})["createStructures" + _cs] = [CC, "createStructures", "(Lnet/minecraft/world/level/chunk/ChunkGenerator;" + _cs[1:]]
for _o in ["net/minecraft/world/level/BlockAndTintGetter", "net/minecraft/client/renderer/block/BlockAndTintGetter"]:
    cr.setdefault(_o, {})["getShade(Lnet/minecraft/core/Direction;Z)F"] = [CC, "getShade", "(Ljava/lang/Object;Lnet/minecraft/core/Direction;Z)F"]
cr.setdefault("net/minecraft/world/level/dimension/DimensionType", {})["effectsLocation()Lnet/minecraft/resources/Identifier;"] = ["foxgrade/shim/LevelCompat", "effectsLocation", "(Lnet/minecraft/world/level/dimension/DimensionType;)Lnet/minecraft/resources/Identifier;"]
cr.setdefault("net/minecraft/world/level/storage/WorldData", {})["worldGenOptions()Lnet/minecraft/world/level/levelgen/WorldOptions;"] = ["foxgrade/shim/LevelCompat", "worldGenOptions", "(Lnet/minecraft/world/level/storage/WorldData;)Lnet/minecraft/world/level/levelgen/WorldOptions;"]
# 1.21 RenderType.create(name, format, mode, size, [crumbling, sort,] CompositeState): RenderTypeCompat rebuilds a 26.2 RenderSetup from the shards.
_CS = "Lnet/minecraft/client/renderer/RenderType$CompositeState;"; _CRT = "Lnet/minecraft/client/renderer/RenderType$CompositeRenderType;"
for _args in ["(Ljava/lang/String;Lcom/mojang/blaze3d/vertex/VertexFormat;Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;IZZ" + _CS + ")", "(Ljava/lang/String;Lcom/mojang/blaze3d/vertex/VertexFormat;Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;I" + _CS + ")"]:
    cr.setdefault("net/minecraft/client/renderer/rendertype/RenderType", {})["create" + _args + _CRT] = ["foxgrade/shim/RenderTypeCompat", "create", _args + RTY]
# PackMetadataSection.TYPE (the one 1.21 section type) -> FALLBACK_TYPE, which reads either pack kind in 26.2.
j["fieldRedirects"].setdefault("net/minecraft/server/packs/metadata/pack/PackMetadataSection", {})["getstatic TYPE:Lnet/minecraft/server/packs/metadata/MetadataSectionType;"] = ["foxgrade/shim/PackCompat", "type", "()Lnet/minecraft/server/packs/metadata/MetadataSectionType;"]
# call adapters: super-calls and re-shaped arguments
EO = ["net/minecraft/world/entity/Entity", "net/minecraft/world/entity/LivingEntity", "net/minecraft/world/entity/Mob", "net/minecraft/world/entity/PathfinderMob", "net/minecraft/world/entity/AgeableMob", "net/minecraft/world/entity/animal/Animal",
      "net/minecraft/world/entity/TamableAnimal", "net/minecraft/world/entity/animal/AbstractGolem", "net/minecraft/world/entity/monster/Monster", "net/minecraft/world/entity/animal/WaterAnimal", "net/minecraft/world/entity/animal/AbstractFish", "net/minecraft/world/entity/animal/AbstractSchoolingFish",
      "net/minecraft/world/entity/FlyingMob", "net/minecraft/world/entity/ambient/AmbientCreature", "net/minecraft/world/entity/animal/ShoulderRidingEntity", "net/minecraft/world/entity/monster/Zombie", "net/minecraft/world/entity/animal/AbstractHorse"]
for o in EO:
    if o not in new: continue
    j["callAdapters"].setdefault(o, {}).update({"dropEquipment()V": {"newName": "dropEquipment", "newDesc": "(" + SL + ")V", "args": [SLV]},
        "interact(" + PL + IH + ")" + IR: {"newName": "interact", "newDesc": "(" + PL + IH + VEC + ")" + IR, "args": ["o1", "o2", ["static", ELC, "zeroVec", "()" + VEC]]}})
j["overrideAdapters"] += [
    {"oldName": "dropEquipment", "oldDesc": "()V", "newName": "dropEquipment", "newDesc": "(" + SL + ")V", "unpack": []},
    {"oldName": "saveAdditional", "oldDesc": "(" + CT + HLP + ")V", "newName": "saveAdditional", "newDesc": "(" + VO + ")V", "unpack": [["static", NB, "tagForOutput", "(" + VO + ")" + CT, "p1"], ["static", NB, "providerOf", "(Ljava/lang/Object;)" + HLP, "this"]], "after": [["static", NB, "flushOutput", "(" + VO + ")V", "p1"]]},
    {"oldName": "loadAdditional", "oldDesc": "(" + CT + HLP + ")V", "newName": "loadAdditional", "newDesc": "(" + VI + ")V", "unpack": [["static", NB, "tagOfInput", "(" + VI + ")" + CT, "p1"], ["static", NB, "providerOf", "(Ljava/lang/Object;)" + HLP, "this"]]},
    {"oldName": "appendHoverText", "oldDesc": "(" + ISK + TTC + LST + TTF + ")V", "newName": "appendHoverText", "newDesc": "(" + ISK + TTC + TTD + CONS + TTF + ")V", "unpack": ["p1", "p2", ["static", ITC, "tooltipList", "(" + CONS + ")" + LST, "p4"], "p5"]},
    {"oldName": "setupRotations", "oldDesc": "(" + LE + PS + "FFFF)V", "newName": "setupRotations", "newDesc": "(" + LERS + PS + "FF)V", "unpack": [["static", ERC, "living", "(" + RS2 + ")" + LE, "p1"], "p2", ["static", ERC, "age", "(" + RS2 + ")F", "p1"], "p3", ["static", ERC, "partial", "(" + RS2 + ")F", "p1"], "p4"]},
]
for o in ["net/minecraft/world/level/block/entity/BlockEntity", "net/minecraft/world/level/block/entity/BaseContainerBlockEntity", "net/minecraft/world/level/block/entity/RandomizableContainerBlockEntity"]:
    if o not in new: continue
    j["callAdapters"].setdefault(o, {}).update({"saveAdditional(" + CT + HLP + ")V": {"newName": "saveAdditional", "newDesc": "(" + VO + ")V", "args": [["static", NB, "outputFor", "(" + CT + ")" + VO, "o1"]]},
        "loadAdditional(" + CT + HLP + ")V": {"newName": "loadAdditional", "newDesc": "(" + VI + ")V", "args": [["static", NB, "inputFor", "(" + CT + ")" + VI, "o1"]]}})
j["callAdapters"].setdefault("net/minecraft/world/item/Item", {})["appendHoverText(" + ISK + TTC + LST + TTF + ")V"] = {"newName": "appendHoverText", "newDesc": "(" + ISK + TTC + TTD + CONS + TTF + ")V", "args": ["o1", "o2", ["static", ITC, "tooltipDisplay", "()" + TTD], ["static", ITC, "tooltipConsumer", "(" + LST + ")" + CONS, "o3"], "o4"]}
STATE_OF = ["static", ERC, "stateOf", "(" + ENT + "Ljava/lang/Object;)" + RS2, "o1", "this"]
for o in ["net/minecraft/client/renderer/entity/EntityRenderer", "net/minecraft/client/renderer/entity/LivingEntityRenderer", "net/minecraft/client/renderer/entity/MobRenderer", "net/minecraft/client/renderer/entity/AgeableMobRenderer",
          "net/minecraft/client/renderer/entity/IllagerRenderer", "net/minecraft/client/renderer/entity/HumanoidMobRenderer", "net/minecraft/client/renderer/entity/AbstractZombieRenderer", "net/minecraft/client/renderer/entity/ArthropodRenderer"]:
    if o not in new: continue
    ca = j["callAdapters"].setdefault(o, {})
    for od in [ENT, LE, MOB]:
        ca["getShadowRadius(" + od + ")F"] = {"newName": "getShadowRadius", "newDesc": "(" + RS2 + ")F", "args": [STATE_OF]}
        ca["shouldShowName(" + od + ")Z"] = {"newName": "shouldShowName", "newDesc": "(" + ENT + "D)Z", "args": ["o1", ["static", ERC, "distSq", "(" + ENT + ")D", "o1"]]}
    ca["setupRotations(" + LE + PS + "FFFF)V"] = {"newName": "setupRotations", "newDesc": "(" + LERS + PS + "FF)V", "args": [["static", ERC, "livingStateOf", "(" + ENT + "Ljava/lang/Object;)" + LERS, "o1", "this"], "o2", "o4", "o6"]}
    ca["getOverlayCoords(" + LE + "F)I"] = {"newName": "getOverlayCoords", "newDesc": "(" + LERS + "F)I", "args": [["static", ERC, "livingState", "(" + ENT + ")" + LERS, "o1"], "o2"]}
j["callAdapters"].setdefault("net/minecraft/world/entity/WalkAnimationState", {})["update(FF)V"] = {"newName": "update", "newDesc": "(FFF)V", "args": ["o1", "o2", ["static", ELC, "one", "()F"]]}
j["callAdapters"].setdefault("net/minecraft/world/level/chunk/ChunkAccess", {})["setBlockState(" + BP + BS2 + "Z)" + BS2] = {"newName": "setBlockState", "newDesc": "(" + BP + BS2 + "I)" + BS2, "args": ["o1", "o2", ["static", BAC, "moveFlags", "(Z)I", "o3"]]}
EMPTY = ["static", ELC, "emptySet", "()" + SET]
j["callAdapters"].setdefault("net/minecraft/world/entity/ai/Brain", {}).update({
    "addActivity(" + ACT + IML + ")V": {"newName": "addActivity", "newDesc": "(" + ACT + IML + SET + SET + ")V", "args": ["o1", "o2", EMPTY, EMPTY]},
    "addActivity(" + ACT + "I" + IML + ")V": {"newName": "addActivity", "newDesc": "(" + ACT + IML + SET + SET + ")V", "args": ["o1", "o3", EMPTY, EMPTY]},
    "addActivityWithConditions(" + ACT + IML + SET + ")V": {"newName": "addActivity", "newDesc": "(" + ACT + IML + SET + SET + ")V", "args": ["o1", "o2", "o3", EMPTY]},
    "addActivityAndRemoveMemoryWhenStopped(" + ACT + "I" + IML + MMT + ")V": {"newName": "addActivity", "newDesc": "(" + ACT + IML + SET + SET + ")V", "args": ["o1", "o3", EMPTY, ["static", ELC, "setOf", "(Ljava/lang/Object;)" + SET, "o4"]]}})
j["callAdapters"].setdefault("net/minecraft/world/entity/ai/targeting/TargetingConditions", {})["test(" + LE + LE + ")Z"] = {"newName": "test", "newDesc": "(" + SL + LE + LE + ")Z", "args": [["static", EAC, "serverLevel", "(Ljava/lang/Object;)" + SL, "o1"], "o1", "o2"]}
print("entity/item batch 3: in")
# ---- retyped constants: same field, wider declared type in 26.2 (ParticleTypes.DRAGON_BREATH: SimpleParticleType → ParticleType)
nretype = 0
for oc, flds in oldF.items():
    slash = oc.replace(".", "/"); nc = j["classRenames"].get(slash, REN.get(slash, slash))
    if nc not in new: continue
    newf = {x.split(":", 1)[0]: x.split(":", 1)[1] for x in new[nc].get("f", []) if ":" in x}
    for fname, odesc in flds.items():
        nd = newf.get(fname)
        if nd is None or nd == odesc or not odesc.startswith("L") or not nd.startswith("L") or nd in HOLDERS or odesc in HOLDERS: continue
        oldcls = odesc[1:-1]
        if oldcls not in new: continue
        key = "getstatic " + fname + ":" + odesc
        if key in j["fieldRedirects"].get(nc, {}): continue
        j["fieldRedirects"].setdefault(nc, {})[key] = ["retype", nd, oldcls]; nretype += 1
print(f"retyped constants bridged: {nretype}")
# ---- batch 3b: block stragglers
cr.setdefault("net/minecraft/world/level/block/state/BlockState", {})["is(Lnet/minecraft/world/level/block/Block;)Z"] = [BAC, "is", "(" + BS2 + "Lnet/minecraft/world/level/block/Block;)Z"]
GC_OLD = "(" + LR + BP + BS2 + ")" + ISK; GC_NEW = "(" + LR + BP + BS2 + "Z)" + ISK
j["overrideAdapters"].append({"oldName": "getCloneItemStack", "oldDesc": GC_OLD, "newName": "getCloneItemStack", "newDesc": GC_NEW, "unpack": ["p1", "p2", "p3"]})
for o in ["net/minecraft/world/level/block/Block", "net/minecraft/world/level/block/state/BlockBehaviour", "net/minecraft/world/level/block/TurtleEggBlock", "net/minecraft/world/level/block/BaseEntityBlock", "net/minecraft/world/level/block/HorizontalDirectionalBlock"]:
    if o in new: j["callAdapters"].setdefault(o, {})["getCloneItemStack" + GC_OLD] = {"newName": "getCloneItemStack", "newDesc": GC_NEW, "args": ["o1", "o2", "o3", ["static", BAC, "falseValue", "()Z"]]}
j["descWidenings"].setdefault("net/minecraft/world/item/DispensibleContainerItem", {}).update({
    "checkExtraContent(" + PL + LVL + ISK + BP + ")V": "(" + LE + LVL + ISK + BP + ")V", "emptyContents(" + PL + LVL + BP + BHR + ")Z": "(" + LE + LVL + BP + BHR + ")Z"})
print("batch 3b: in")
# ---- reload listeners: 1.21.x (barrier, manager, profiler, profiler, executor, executor) → 26.2 (sharedState, executor, barrier, executor)
RLC = "foxgrade/shim/ReloadCompat"; PRB = "Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;"; SST = "Lnet/minecraft/server/packs/resources/PreparableReloadListener$SharedState;"
RSM = "Lnet/minecraft/server/packs/resources/ResourceManager;"; EXE = "Ljava/util/concurrent/Executor;"; CF = "Ljava/util/concurrent/CompletableFuture;"; CODEC = "Lcom/mojang/serialization/Codec;"; F2I = "Lnet/minecraft/resources/FileToIdConverter;"
RL_OLD = "(" + PRB + RSM + PF + PF + EXE + EXE + ")" + CF; RL_NEW = "(" + SST + EXE + PRB + EXE + ")" + CF
j["overrideAdapters"].append({"oldName": "reload", "oldDesc": RL_OLD, "newName": "reload", "newDesc": RL_NEW,
    "unpack": ["p3", ["static", RLC, "resourceManager", "(" + SST + ")" + RSM, "p1"], ["static", RLC, "profiler", "()" + PF], ["static", RLC, "profiler", "()" + PF], "p2", "p4"]})
for o in ["net/minecraft/server/packs/resources/PreparableReloadListener", "net/minecraft/server/packs/resources/SimplePreparableReloadListener", "net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener"]:
    if o in new: j["callAdapters"].setdefault(o, {})["reload" + RL_OLD] = {"newName": "reload", "newDesc": RL_NEW, "args": [["static", RLC, "sharedState", "(" + RSM + ")" + SST, "o2"], "o5", "o1", "o6"]}
j["ctorAdapters"].setdefault("net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener", {})["(Lcom/google/gson/Gson;Ljava/lang/String;)V"] = {"newDesc": "(" + CODEC + F2I + ")V",
    "args": [["static", RLC, "jsonCodec", "()" + CODEC], ["static", RLC, "jsonConverter", "(Ljava/lang/String;)" + F2I, "o2"]]}
j["descWidenings"].setdefault("net/minecraft/world/level/block/Block", {})["getDrops(" + BS2 + SL + BP + "Lnet/minecraft/world/level/block/entity/BlockEntity;" + ENT + ISK + ")" + LST] = "(" + BS2 + SL + BP + "Lnet/minecraft/world/level/block/entity/BlockEntity;" + ENT + "Lnet/minecraft/world/item/ItemInstance;)" + LST
print("reload listeners: in")
# ======================= 1.20.1-era shapes (first batch; the immediate-mode drawing layer is not here yet) =======================
MEF = "Lnet/minecraft/world/effect/MobEffect;"; FOOD = "Lnet/minecraft/world/food/FoodProperties;"; OPTS = "Lnet/minecraft/client/Options;"; GUI = "Lnet/minecraft/client/gui/Gui;"; TMG = "Lnet/minecraft/client/renderer/texture/TextureManager;"
CPOS = "Lnet/minecraft/world/level/ChunkPos;"
j["ctorAdapters"].setdefault("net/minecraft/resources/Identifier", {})["(Ljava/lang/String;)V"] = {"factory": ["net/minecraft/resources/Identifier", "parse", "(Ljava/lang/String;)" + ID]}
j["ctorAdapters"].setdefault("net/minecraft/world/level/ChunkPos", {})["(" + BP + ")V"] = {"factory": ["net/minecraft/world/level/ChunkPos", "containing", "(" + BP + ")" + CPOS]}
for x in new["net/minecraft/world/effect/MobEffects"]["f"]:
    fname, nd = x.split(":", 1)
    if nd in HOLDERS and fname.isupper(): j["fieldRedirects"].setdefault("net/minecraft/world/effect/MobEffects", {})["getstatic " + fname + ":" + MEF] = ["holder", nd, "foxgrade/shim/HolderCompat", "value", "(Lnet/minecraft/core/Holder;)Ljava/lang/Object;", "net/minecraft/world/effect/MobEffect"]
cr.setdefault("net/minecraft/world/effect/MobEffectInstance", {})["getEffect()" + MEF] = [EFC, "effectOf", "(" + MEI + ")" + MEF]
cr["net/minecraft/world/entity/LivingEntity"].update({"hasEffect(" + MEF + ")Z": [EFC, "hasEffect", "(" + LE + MEF + ")Z"], "getEffect(" + MEF + ")" + MEI: [EFC, "getEffect", "(" + LE + MEF + ")" + MEI]})
j["renames"].setdefault("net/minecraft/world/entity/Entity", {})["getCommandSenderWorld"] = "level"
j["renames"].setdefault("net/minecraft/world/food/FoodProperties", {}).update({"getNutrition": "nutrition", "getSaturationModifier": "saturation"})
cr.setdefault("net/minecraft/world/food/FoodProperties", {})["getEffects()" + LST] = [ITC, "foodEffects", "(" + FOOD + ")" + LST]
cr.setdefault("net/minecraft/world/item/Item", {}).update({"getFoodProperties()" + FOOD: [ITC, "getFoodProperties", "(" + ITEM + ")" + FOOD], "isEdible()Z": [ITC, "isEdible", "(" + ITEM + ")Z"]})
j["fieldRedirects"].setdefault("net/minecraft/network/chat/ComponentContents", {})["getstatic EMPTY:Lnet/minecraft/network/chat/ComponentContents;"] = ["move", "net/minecraft/network/chat/contents/PlainTextContents", "Lnet/minecraft/network/chat/contents/PlainTextContents;"]
cr.setdefault("net/minecraft/client/renderer/texture/TextureManager", {})["bindForSetup(" + ID + ")V"] = [MCC, "bindForSetup", "(" + TMG + ID + ")V"]
cr.setdefault("net/minecraft/client/gui/Gui", {})["getGuiTicks()I"] = [MCC, "getGuiTicks", "(" + GUI + ")I"]
j["fieldRedirects"].setdefault("net/minecraft/client/Options", {}).update({"get renderDebug:Z": [MCC, "renderDebug", "(" + OPTS + ")Z"], "put renderDebug:Z": [MCC, "setRenderDebug", "(" + OPTS + "Z)V"],
    "get renderDebugCharts:Z": [MCC, "renderDebugCharts", "(" + OPTS + ")Z"], "get renderFpsChart:Z": [MCC, "renderFpsChart", "(" + OPTS + ")Z"]})
print("1.20.1 batch 1: in")
# ======================= registration: 26.2 wants registry ids before construction =======================
RK = "Lnet/minecraft/resources/ResourceKey;"; RGC = "foxgrade/shim/RegistryCompat"; REG = "Lnet/minecraft/core/Registry;"; HREF = "Lnet/minecraft/core/Holder$Reference;"; BBP = "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;"; BBH = "Lnet/minecraft/world/level/block/state/BlockBehaviour;"
AMAT = "Lnet/minecraft/world/item/equipment/ArmorMaterial;"; SUP = "Ljava/util/function/Supplier;"; MAP = "Ljava/util/Map;"
j["ctorAdapters"].setdefault("net/minecraft/world/item/Item$Properties", {})["()V"] = {"factory": [ITC, "properties", "()" + IPR]}
cr.setdefault("net/minecraft/world/level/block/state/BlockBehaviour$Properties", {}).update({"of()" + BBP: [BAC, "blockProperties", "()" + BBP], "ofFullCopy(" + BBH + ")" + BBP: [BAC, "ofFullCopy", "(" + BBH + ")" + BBP], "ofLegacyCopy(" + BBH + ")" + BBP: [BAC, "ofLegacyCopy", "(" + BBH + ")" + BBP]})
cr.setdefault("net/minecraft/core/Registry", {}).update({
    "register(" + REG + "Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;": [RGC, "register", "(" + REG + "Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;"],
    "register(" + REG + ID + "Ljava/lang/Object;)Ljava/lang/Object;": [RGC, "register", "(" + REG + ID + "Ljava/lang/Object;)Ljava/lang/Object;"],
    "register(" + REG + RK + "Ljava/lang/Object;)Ljava/lang/Object;": [RGC, "register", "(" + REG + RK + "Ljava/lang/Object;)Ljava/lang/Object;"],
    "registerForHolder(" + REG + ID + "Ljava/lang/Object;)" + HREF: [RGC, "registerForHolder", "(" + REG + ID + "Ljava/lang/Object;)" + HREF],
    "registerForHolder(" + REG + RK + "Ljava/lang/Object;)" + HREF: [RGC, "registerForHolder", "(" + REG + RK + "Ljava/lang/Object;)" + HREF]})
j["fieldRedirects"].setdefault("net/minecraft/core/registries/BuiltInRegistries", {})["getstatic ARMOR_MATERIAL:" + REG] = [RGC, "armorMaterialRegistry", "()" + REG]
j["ctorAdapters"].setdefault("net/minecraft/world/item/equipment/ArmorMaterial", {})["(" + MAP + "I" + HOLD + SUP + LST + "FF)V"] = {"factory": [RGC, "armorMaterial", "(" + MAP + "I" + HOLD + SUP + LST + "FF)" + AMAT]}
# ---- built-in registries 26.2 removed (ARMOR_MATERIAL, SCHEDULE, INSTRUMENT, …): mods register into them at init;
# an empty in-memory registry keeps that working (the values then drive nothing, which is what 26.2 does with them).
newf_bir = {x.split(":")[0] for x in new["net/minecraft/core/registries/BuiltInRegistries"]["f"]}
old_bir = sorted(n for n, d in oldF.get("net.minecraft.core.registries.BuiltInRegistries", {}).items() if n.isupper() and d == "Lnet/minecraft/core/Registry;" and n not in newf_bir)
rlines = []
for n in old_bir:
    meth = "reg_" + n.lower()
    rlines.append(f'  private static net.minecraft.core.MappedRegistry<Object> {n};\n  @SuppressWarnings({{"unchecked", "rawtypes"}})\n  public static synchronized net.minecraft.core.Registry<?> {meth}() {{ if ({n} == null) {n} = new net.minecraft.core.MappedRegistry(net.minecraft.resources.ResourceKey.createRegistryKey(net.minecraft.resources.Identifier.fromNamespaceAndPath("foxgrade", "{n.lower()}")), com.mojang.serialization.Lifecycle.stable()); return {n}; }}')
    j["fieldRedirects"].setdefault("net/minecraft/core/registries/BuiltInRegistries", {})["getstatic " + n + ":" + REG] = ["foxgrade/shim/RegistriesCompat", meth, "()" + REG]
    # the matching Registries.<N> ResourceKey constant (bookshelf reads Registries.ITEM_SUB_PREDICATE_TYPE)
    if n not in {x.split(":")[0] for x in new["net/minecraft/core/registries/Registries"]["f"]}:
        rlines.append(f'  public static net.minecraft.resources.ResourceKey<?> key_{n.lower()}() {{ return {meth}().key(); }}\n')
        j["fieldRedirects"].setdefault("net/minecraft/core/registries/Registries", {})["getstatic " + n + ":Lnet/minecraft/resources/ResourceKey;"] = ["foxgrade/shim/RegistriesCompat", "key_" + n.lower(), "()Lnet/minecraft/resources/ResourceKey;"]
(MOD / "src/main/java/foxgrade/shim/RegistriesCompat.java").write_text("package foxgrade.shim;\n\n/** Built-in registries 1.21.x mods write into that 26.2 no longer has; generated by gen-render-bridges.py. */\npublic final class RegistriesCompat {\n  private RegistriesCompat() {}\n" + "\n".join(rlines) + "\n}\n")
print(f"removed built-in registries shimmed: {old_bir}")
print("registration: in")
SDB = "Lnet/minecraft/world/level/block/state/StateDefinition$Builder;"; PROP = "[Lnet/minecraft/world/level/block/state/properties/Property;"
cr.setdefault("net/minecraft/world/level/block/state/StateDefinition$Builder", {})["add(" + PROP + ")" + SDB] = [BAC, "add", "(" + SDB + PROP + ")" + SDB]
print("state builder: in")
VP = "Lnet/minecraft/world/entity/npc/villager/VillagerProfession;"; IMS = "Lcom/google/common/collect/ImmutableSet;"; SE2 = "Lnet/minecraft/sounds/SoundEvent;"
j["ctorAdapters"].setdefault("net/minecraft/world/entity/npc/villager/VillagerProfession", {})["(Ljava/lang/String;" + PRED + PRED + IMS + IMS + SE2 + ")V"] = {"factory": ["foxgrade/shim/VillagerCompat", "profession", "(Ljava/lang/String;" + PRED + PRED + IMS + IMS + SE2 + ")" + VP]}
print("villager profession: in")
# ======================= batch 4: what the fresh 1.21.1 set (appleskin, jei, rei, waystones, balm, trinkets, ncr…) still hit =======================
CHC = "foxgrade/shim/ChatCompat"; PMC = "foxgrade/shim/PermissionCompat"; CSS = "Lnet/minecraft/commands/CommandSourceStack;"; TAGT = "Lnet/minecraft/nbt/Tag;"; CE = "Lnet/minecraft/network/chat/ClickEvent;"
CEA = "Lnet/minecraft/network/chat/ClickEvent$Action;"; HE = "Lnet/minecraft/network/chat/HoverEvent;"; HEA = "Lnet/minecraft/network/chat/HoverEvent$Action;"; STY = "Lnet/minecraft/network/chat/Style;"
UUIDT = "Ljava/util/UUID;"; MSV = "Lnet/minecraft/server/MinecraftServer;"; AMOD = "Lnet/minecraft/world/entity/ai/attributes/AttributeModifier;"; RCP = "Lnet/minecraft/world/item/crafting/Recipe;"; NNL2 = "Lnet/minecraft/core/NonNullList;"
RLK = "Lnet/minecraft/core/HolderLookup$RegistryLookup;"; EEQ = "Lnet/minecraft/world/entity/EntityEquipment;"; CLV = "Lnet/minecraft/client/multiplayer/ClientLevel;"; LPL = "Lnet/minecraft/client/player/LocalPlayer;"
cr.setdefault("net/minecraft/commands/CommandSourceStack", {})["hasPermission(I)Z"] = [PMC, "hasPermission", "(" + CSS + "I)Z"]
cr.setdefault("net/minecraft/nbt/Tag", {})["getAsString()Ljava/lang/String;"] = [NC, "getAsString", "(" + TAGT + ")Ljava/lang/String;"]
cr["net/minecraft/nbt/CompoundTag"]["remove(Ljava/lang/String;)V"] = [NC, "remove", "(" + CT + "Ljava/lang/String;)V"]
cr.setdefault("net/minecraft/nbt/NbtUtils", {}).update({"createUUID(" + UUIDT + ")" + TAGT: [NC, "createUUID", "(" + UUIDT + ")" + TAGT], "loadUUID(" + TAGT + ")" + UUIDT: [NC, "loadUUID", "(" + TAGT + ")" + UUIDT],
    "readBlockPos(" + CT + "Ljava/lang/String;)" + OPT: [NC, "readBlockPos", "(" + CT + "Ljava/lang/String;)" + OPT], "writeBlockPos(" + BP + ")" + TAGT: [NC, "writeBlockPos", "(" + BP + ")" + TAGT]})
cr["net/minecraft/world/item/ItemStack"].update({"parseOptional(" + HLP + CT + ")" + ISK: [NC, "parseOptional", "(" + HLP + CT + ")" + ISK], "saveOptional(" + HLP + ")" + TAGT: [NC, "saveOptional", "(" + ISK + HLP + ")" + TAGT]})
cr.setdefault("net/minecraft/world/entity/ai/attributes/AttributeModifier", {}).update({"save()" + CT: [NC, "saveModifier", "(" + AMOD + ")" + CT], "load(" + CT + ")" + AMOD: [NC, "loadModifier", "(" + CT + ")" + AMOD]})
cr["net/minecraft/world/entity/Entity"]["getServer()" + MSV] = [ELC, "getServer", "(" + ENT + ")" + MSV]
cr["net/minecraft/world/entity/player/Player"]["createCommandSourceStack()" + CSS] = [ELC, "createCommandSourceStack", "(" + PL + ")" + CSS]
cr.setdefault("net/minecraft/core/BlockPos", {})["getCenter()" + VEC] = [ELC, "getCenter", "(" + BP + ")" + VEC]
cr.setdefault("net/minecraft/network/chat/ClickEvent", {})["getValue()Ljava/lang/String;"] = [CHC, "clickValue", "(" + CE + ")Ljava/lang/String;"]
cr.setdefault("net/minecraft/network/chat/HoverEvent", {})["getValue(" + HEA + ")Ljava/lang/Object;"] = [CHC, "hoverValue", "(" + HE + HEA + ")Ljava/lang/Object;"]
cr.setdefault("net/minecraft/network/chat/Style", {})["withFont(" + ID + ")" + STY] = [CHC, "withFont", "(" + STY + ID + ")" + STY]
for o in ["net/minecraft/world/item/crafting/Recipe", "net/minecraft/world/item/crafting/CraftingRecipe", "net/minecraft/world/item/crafting/AbstractCookingRecipe", "net/minecraft/world/item/crafting/CampfireCookingRecipe", "net/minecraft/world/item/crafting/SmeltingRecipe", "net/minecraft/world/item/crafting/ShapedRecipe", "net/minecraft/world/item/crafting/ShapelessRecipe"]:
    if o in new: cr.setdefault(o, {})["getIngredients()" + NNL2] = [RC, "getIngredients", "(" + RCP + ")" + NNL2]
j["fieldRedirects"].setdefault("net/minecraft/world/item/crafting/Ingredient", {})["getstatic EMPTY:" + ING] = [RC, "emptyIngredient", "()" + ING]
cr.setdefault("net/minecraft/core/Registry", {})["asLookup()" + RLK] = [RC, "asLookup", "(" + REG + ")" + RLK]
j["renames"].setdefault("net/minecraft/core/Registry", {}).update({"getTag": "get", "getTagNames": "listTagIds"})
fr = j["fieldRedirects"]
fr.setdefault("net/minecraft/client/player/LocalPlayer", {})["get clientLevel:" + CLV] = [MCC, "clientLevel", "(" + LPL + ")" + CLV]
# constructors
j["ctorAdapters"].setdefault("net/minecraft/network/chat/ClickEvent", {})["(" + CEA + "Ljava/lang/String;)V"] = {"factory": [CHC, "clickEvent", "(" + CEA + "Ljava/lang/String;)" + CE]}
j["ctorAdapters"].setdefault("net/minecraft/network/chat/HoverEvent", {})["(" + HEA + "Ljava/lang/Object;)V"] = {"factory": [CHC, "hoverEvent", "(" + HEA + "Ljava/lang/Object;)" + HE]}
j["ctorAdapters"].setdefault("net/minecraft/core/NonNullList", {})["()V"] = {"factory": ["net/minecraft/core/NonNullList", "create", "()" + NNL2]}
j["ctorAdapters"].setdefault("net/minecraft/client/gui/screens/ChatScreen", {})["(Ljava/lang/String;)V"] = {"newDesc": "(Ljava/lang/String;Z)V", "transforms": [{"slot": -2, "via": [ELC, "falseValue", "()Z"]}]}
j["ctorAdapters"].setdefault("net/minecraft/world/entity/player/Inventory", {})["(" + PL + ")V"] = {"newDesc": "(" + PL + EEQ + ")V", "args": ["o1", ["static", ELC, "newEquipment", "()" + EEQ]]}
# calls that grew a parameter
for o in EO:
    if o not in new: continue
    j["callAdapters"].setdefault(o, {}).update({"startRiding(" + ENT + "Z)Z": {"newName": "startRiding", "newDesc": "(" + ENT + "ZZ)Z", "args": ["o1", "o2", ["static", ELC, "falseValue", "()Z"]]},
        "teleportTo(" + SL + "DDD" + SET + "FF)Z": {"newName": "teleportTo", "newDesc": "(" + SL + "DDD" + SET + "FFZ)Z", "args": ["o1", "o2", "o3", "o4", "o5", "o6", "o7", ["static", ELC, "falseValue", "()Z"]]}})
j["callAdapters"].setdefault("net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener", {})["scanDirectory(" + RSM + "Ljava/lang/String;Lcom/google/gson/Gson;Ljava/util/Map;)V"] = {"newName": "scanDirectory", "newDesc": "(" + RSM + F2I + "Lcom/mojang/serialization/DynamicOps;" + CODEC + "Ljava/util/Map;)V",
    "args": ["o1", ["static", RLC, "jsonConverter", "(Ljava/lang/String;)" + F2I, "o2"], ["static", RLC, "jsonOps", "()Lcom/mojang/serialization/DynamicOps;"], ["static", RLC, "jsonCodec", "()" + CODEC], "o4"]}
# super.use(...) on items: 26.2 returns InteractionResult; the 1.21.x caller expects a holder
for o in ["net/minecraft/world/item/Item", "net/minecraft/world/item/BlockItem", "net/minecraft/world/item/BucketItem", "net/minecraft/world/item/MobBucketItem", "net/minecraft/world/item/ArmorItem"]:
    if o in new or o == "net/minecraft/world/item/ArmorItem":
        j["callAdapters"].setdefault(o, {})["use(" + LVL + PL + IH + ")" + IRH] = {"newName": "use", "newDesc": "(" + LVL + PL + IH + ")" + IR, "args": ["o1", "o2", "o3"], "convert": ["static", "foxgrade/shim/InteractionCompat", "toHolder", "(" + IR + ")" + IRH]}
# Fabric screen events: render → extract
SEV = "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents"
j["renames"].setdefault(SEV, {}).update({"beforeRender": "beforeExtract", "afterRender": "afterExtract"})
j["classRenames"].update({SEV + "$BeforeRender": SEV + "$BeforeExtract", SEV + "$AfterRender": SEV + "$AfterExtract"})
j.setdefault("samRenames", {}).update({SEV + "$BeforeExtract": {"beforeRender": "beforeExtract"}, SEV + "$AfterExtract": {"afterRender": "afterExtract"}})
# a CLASS implementing the callback (not a lambda) declares beforeRender(...) — rename the declaration through the
# interface it implements, looked up under the old name (what the class file says) and the new one (post-rename)
for _i in ("$BeforeRender", "$BeforeExtract"): j["renames"].setdefault(SEV + _i, {})["beforeRender"] = "beforeExtract"
for _i in ("$AfterRender", "$AfterExtract"): j["renames"].setdefault(SEV + _i, {})["afterRender"] = "afterExtract"
j["fieldRedirects"].setdefault("net/minecraft/world/item/ItemStack", {})["getstatic STRICT_CODEC:Lcom/mojang/serialization/Codec;"] = ["move", "net/minecraft/world/item/ItemStack", "Lcom/mojang/serialization/Codec;", "CODEC"]

# ---- Fabric API renames 1.21.x → 26.2 modules (item groups → creative tabs, screen handlers → menus)
FAPI = "net/fabricmc/fabric/api/"
j["classRenames"].update({
    FAPI + "itemgroup/v1/ItemGroupEvents": FAPI + "creativetab/v1/CreativeModeTabEvents",
    FAPI + "itemgroup/v1/ItemGroupEvents$ModifyEntries": FAPI + "creativetab/v1/CreativeModeTabEvents$ModifyOutput",
    FAPI + "itemgroup/v1/ItemGroupEvents$ModifyEntriesAll": FAPI + "creativetab/v1/CreativeModeTabEvents$ModifyOutputAll",
    FAPI + "itemgroup/v1/FabricItemGroupEntries": FAPI + "creativetab/v1/FabricCreativeModeTabOutput",
    FAPI + "itemgroup/v1/FabricItemGroup": FAPI + "creativetab/v1/FabricCreativeModeTab",
    FAPI + "screenhandler/v1/ExtendedScreenHandlerType": FAPI + "menu/v1/ExtendedMenuType",
    FAPI + "screenhandler/v1/ExtendedScreenHandlerType$ExtendedFactory": FAPI + "menu/v1/ExtendedMenuType$ExtendedFactory",
    FAPI + "screenhandler/v1/ExtendedScreenHandlerFactory": FAPI + "menu/v1/ExtendedMenuProvider"})
j["renames"].setdefault(FAPI + "creativetab/v1/CreativeModeTabEvents", {})["modifyEntriesEvent"] = "modifyOutputEvent"
j["fieldRedirects"].setdefault(FAPI + "creativetab/v1/CreativeModeTabEvents", {})["getstatic MODIFY_ENTRIES_ALL:Lnet/fabricmc/fabric/api/event/Event;"] = ["move", FAPI + "creativetab/v1/CreativeModeTabEvents", "Lnet/fabricmc/fabric/api/event/Event;", "MODIFY_OUTPUT_ALL"]
j.setdefault("samRenames", {}).update({FAPI + "creativetab/v1/CreativeModeTabEvents$ModifyOutput": {"modifyEntries": "modifyOutput"}, FAPI + "creativetab/v1/CreativeModeTabEvents$ModifyOutputAll": {"modifyEntries": "modifyOutput"}})
j["renames"].setdefault(FAPI + "event/registry/FabricRegistryBuilder", {})["createSimple"] = "create"
j["renames"].setdefault(FAPI + "biome/v1/BiomeModificationContext", {})["getSpawnSettings"] = "getMobSpawnSettings"
j["renames"].setdefault(FAPI + "biome/v1/BiomeSelectionContext", {})["getBiomeRegistryEntry"] = "getBiomeHolder"
cr.setdefault("net/minecraft/world/item/Item", {})["getCraftingRemainingItem()" + ITEM] = [ITC, "craftingRemainingItem", "(" + ITEM + ")" + ITEM]
cr.setdefault(FAPI + "item/v1/FabricItemStack", {})["getRecipeRemainder()" + ISK] = [ITC, "getRecipeRemainderOf", "(Ljava/lang/Object;)" + ISK]
j["classRenames"][FAPI + "biome/v1/BiomeModificationContext$SpawnSettingsContext"] = FAPI + "biome/v1/BiomeModificationContext$MobSpawnSettingsContext"
cr.setdefault("net/minecraft/world/item/ItemStack", {})["getRecipeRemainder()" + ISK] = [ITC, "getRecipeRemainder", "(" + ISK + ")" + ISK]
MLC = FAPI + "client/model/loading/v1/ModelLoadingPlugin$Context"
cr.setdefault(MLC, {}).update({"addModels(Ljava/util/Collection;)V": [MOC, "addModels", "(Ljava/lang/Object;Ljava/util/Collection;)V"], "addModels([" + ID + ")V": [MOC, "addModels", "(Ljava/lang/Object;[" + ID + ")V"]})
BET = "Lnet/minecraft/world/level/block/entity/BlockEntityType;"; ARMP = "Lnet/minecraft/client/model/HumanoidModel$ArmPose;"
j["fieldRedirects"].setdefault("net/minecraft/world/level/block/entity/BlockEntityType", {})["getstatic BED:" + BET] = [BAC, "bedBlockEntityType", "()" + BET]
j["fieldRedirects"].setdefault("net/minecraft/client/model/HumanoidModel$ArmPose", {})["getstatic THROW_SPEAR:" + ARMP] = ["move", "net/minecraft/client/model/HumanoidModel$ArmPose", ARMP, "THROW_TRIDENT"]
MLP = FAPI + "client/model/loading/v1/ModelLoadingPlugin"
j["samRenames"].setdefault(MLP, {})["onInitializeModelLoader"] = "initialize"
j["renames"].setdefault(MLP, {})["onInitializeModelLoader"] = "initialize"
RMD = "Lnet/minecraft/server/packs/resources/ResourceMetadata;"; MSS = "Lnet/minecraft/server/packs/metadata/MetadataSectionSerializer;"
cr.setdefault("net/minecraft/server/packs/resources/ResourceMetadata", {})["getSection(" + MSS + ")" + OPT] = ["foxgrade/shim/ResourceMetadataCompat", "getSection", "(" + RMD + MSS + ")" + OPT]
# PackResources.getMetadataSection(MetadataSectionSerializer) -> the codec-typed section in 26.2 (fancymenu).
_PKR = "Lnet/minecraft/server/packs/PackResources;"
cr.setdefault("net/minecraft/server/packs/PackResources", {})["getMetadataSection(" + MSS + ")Ljava/lang/Object;"] = ["foxgrade/shim/ResourceMetadataCompat", "packSection", "(" + _PKR + MSS + ")Ljava/lang/Object;"]
# PackResources.getMetadataSection(MetadataSectionSerializer) is getMetadataSection(MetadataSectionType) now: a mod's old override
# gets a bridge with the new signature, handing the section type over through the 1.21 interface.
MST = "Lnet/minecraft/server/packs/metadata/MetadataSectionType;"
j["overrideAdapters"].append({"oldName": "getMetadataSection", "oldDesc": "(" + MSS + ")Ljava/lang/Object;", "newName": "getMetadataSection", "newDesc": "(" + MST + ")Ljava/lang/Object;", "unpack": [["static", "foxgrade/shim/ResourceMetadataCompat", "serializer", "(" + MST + ")" + MSS, "p1"]]})
cr.setdefault("net/minecraft/server/MinecraftServer", {})["getProfilePermissions(Lcom/mojang/authlib/GameProfile;)I"] = [PMC, "getProfilePermissions", "(" + MSV + "Lcom/mojang/authlib/GameProfile;)I"]
j["inheritedRenamesByAncestor"].setdefault("onInitializeModelLoader(L" + MLP + "$Context;)V", []).append([MLP, "initialize"])
SDS = "Lnet/minecraft/world/level/storage/SavedDataStorage;"; SDF = "Lnet/minecraft/world/level/saveddata/SavedData$Factory;"; SDD = "Lnet/minecraft/world/level/saveddata/SavedData;"; SDC = "foxgrade/shim/SavedDataCompat"
j["classRenames"]["net/minecraft/world/level/storage/DimensionDataStorage"] = "net/minecraft/world/level/storage/SavedDataStorage"
cr.setdefault("net/minecraft/world/level/storage/SavedDataStorage", {}).update({"computeIfAbsent(" + SDF + "Ljava/lang/String;)" + SDD: [SDC, "computeIfAbsent", "(" + SDS + SDF + "Ljava/lang/String;)" + SDD],
    "get(" + SDF + "Ljava/lang/String;)" + SDD: [SDC, "get", "(" + SDS + SDF + "Ljava/lang/String;)" + SDD], "set(Ljava/lang/String;" + SDD + ")V": [SDC, "set", "(" + SDS + "Ljava/lang/String;" + SDD + ")V"]})
cr["net/minecraft/server/MinecraftServer"]["isSingleplayerOwner(Lcom/mojang/authlib/GameProfile;)Z"] = [PMC, "isSingleplayerOwner", "(" + MSV + "Lcom/mojang/authlib/GameProfile;)Z"]
j["classRenames"]["net/minecraft/client/resources/PlayerSkin$Model"] = "net/minecraft/world/entity/player/PlayerModelType"
# Fabric lifecycle callbacks renamed World → Level (1.21.x → 26.2); implementors (lambdas and classes) must follow
LCE = FAPI + "event/lifecycle/v1/"; CLE = FAPI + "client/event/lifecycle/v1/"
for itf, old_n, new_n in [(LCE + "ServerLevelEvents$Load", "onWorldLoad", "onLevelLoad"), (LCE + "ServerLevelEvents$Unload", "onWorldUnload", "onLevelUnload"),
                          (CLE + "ClientLevelEvents$AfterClientLevelChange", "afterWorldChange", "afterLevelChange"),
                          (LCE + "ServerTickEvents$StartLevelTick", "onStartTick", "onStartTick"), (LCE + "ServerTickEvents$EndLevelTick", "onEndTick", "onEndTick")]:
    if old_n != new_n:
        j.setdefault("samRenames", {}).setdefault(itf, {})[old_n] = new_n
        j["renames"].setdefault(itf, {})[old_n] = new_n
for old_c, new_c in [(LCE + "ServerWorldEvents", LCE + "ServerLevelEvents"), (LCE + "ServerWorldEvents$Load", LCE + "ServerLevelEvents$Load"), (LCE + "ServerWorldEvents$Unload", LCE + "ServerLevelEvents$Unload"),
                     (CLE + "ClientWorldEvents", CLE + "ClientLevelEvents"), (CLE + "ClientWorldEvents$AfterClientWorldChange", CLE + "ClientLevelEvents$AfterClientLevelChange"),
                     (LCE + "ServerTickEvents$StartWorldTick", LCE + "ServerTickEvents$StartLevelTick"), (LCE + "ServerTickEvents$EndWorldTick", LCE + "ServerTickEvents$EndLevelTick"),
                     (CLE + "ClientTickEvents$StartWorldTick", CLE + "ClientTickEvents$StartLevelTick"), (CLE + "ClientTickEvents$EndWorldTick", CLE + "ClientTickEvents$EndLevelTick")]:
    j["classRenames"].setdefault(old_c, new_c)
j["fieldRedirects"].setdefault(LCE + "ServerTickEvents", {}).update({"getstatic START_WORLD_TICK:Lnet/fabricmc/fabric/api/event/Event;": ["move", LCE + "ServerTickEvents", "Lnet/fabricmc/fabric/api/event/Event;", "START_LEVEL_TICK"], "getstatic END_WORLD_TICK:Lnet/fabricmc/fabric/api/event/Event;": ["move", LCE + "ServerTickEvents", "Lnet/fabricmc/fabric/api/event/Event;", "END_LEVEL_TICK"]})
j["fieldRedirects"].setdefault(CLE + "ClientTickEvents", {}).update({"getstatic START_WORLD_TICK:Lnet/fabricmc/fabric/api/event/Event;": ["move", CLE + "ClientTickEvents", "Lnet/fabricmc/fabric/api/event/Event;", "START_LEVEL_TICK"], "getstatic END_WORLD_TICK:Lnet/fabricmc/fabric/api/event/Event;": ["move", CLE + "ClientTickEvents", "Lnet/fabricmc/fabric/api/event/Event;", "END_LEVEL_TICK"]})
j["fieldRedirects"].setdefault(CLE + "ClientLevelEvents", {})["getstatic AFTER_CLIENT_WORLD_CHANGE:Lnet/fabricmc/fabric/api/event/Event;"] = ["move", CLE + "ClientLevelEvents", "Lnet/fabricmc/fabric/api/event/Event;", "AFTER_CLIENT_LEVEL_CHANGE"]
j["fieldRedirects"].setdefault(LCE + "ServerLevelEvents", {}).update({"getstatic LOAD:Lnet/fabricmc/fabric/api/event/Event;": ["move", LCE + "ServerLevelEvents", "Lnet/fabricmc/fabric/api/event/Event;", "LOAD"]})
j["fieldRedirects"].setdefault("net/minecraft/world/item/crafting/Ingredient", {})["getstatic CODEC_NONEMPTY:Lcom/mojang/serialization/Codec;"] = ["move", "net/minecraft/world/item/crafting/Ingredient", "Lcom/mojang/serialization/Codec;", "CODEC"]
HEV = "Lnet/minecraft/network/chat/HoverEvent;"; SHI = "Lnet/minecraft/network/chat/HoverEvent$ShowItem;"; BSS = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;"; SCR = "Lnet/minecraft/client/gui/screens/Screen;"
j["classRenames"]["net/minecraft/network/chat/HoverEvent$ItemStackInfo"] = "net/minecraft/network/chat/HoverEvent$ShowItem"
j["ctorAdapters"].setdefault("net/minecraft/network/chat/HoverEvent$ShowItem", {})["(" + ISK + ")V"] = {"factory": [CHC, "showItem", "(" + ISK + ")" + SHI]}
cr.setdefault("net/minecraft/client/gui/GuiGraphicsExtractor", {})["bufferSource()" + BSS] = [GC, "bufferSource", "(" + GGE + ")" + BSS]
cr.setdefault("net/minecraft/client/gui/screens/Screen", {})["handleComponentClicked(" + STY + ")Z"] = ["foxgrade/shim/ScreenCompat", "handleComponentClicked", "(" + SCR + STY + ")Z"]
SSP = "Lnet/minecraft/client/StringSplitter;"
cr.setdefault("net/minecraft/client/StringSplitter", {}).update({"componentStyleAtWidth(Lnet/minecraft/util/FormattedCharSequence;I)" + STY: ["foxgrade/shim/StringSplitterCompat", "componentStyleAtWidth", "(" + SSP + "Lnet/minecraft/util/FormattedCharSequence;I)" + STY],
    "componentStyleAtWidth(Lnet/minecraft/network/chat/FormattedText;I)" + STY: ["foxgrade/shim/StringSplitterCompat", "componentStyleAtWidth", "(" + SSP + "Lnet/minecraft/network/chat/FormattedText;I)" + STY]})
FNT = "Lnet/minecraft/client/gui/Font;"; MBS = "Lnet/minecraft/client/renderer/MultiBufferSource;"; DM = "Lnet/minecraft/client/gui/Font$DisplayMode;"
cr.setdefault("net/minecraft/client/gui/Font", {})["renderText(Ljava/lang/String;FFIZLorg/joml/Matrix4f;" + MBS + DM + "II)I"] = ["foxgrade/shim/FrameCompat", "renderText", "(" + FNT + "Ljava/lang/String;FFIZLorg/joml/Matrix4f;" + MBS + DM + "II)I"]
cr["net/minecraft/world/entity/LivingEntity"]["getArmorSlots()Ljava/lang/Iterable;"] = [ELC, "getArmorSlots", "(" + LE + ")Ljava/lang/Iterable;"]
cr["net/minecraft/world/item/Item"]["components()Lnet/minecraft/core/component/DataComponentMap;"] = [ITC, "components", "(" + ITEM + ")Lnet/minecraft/core/component/DataComponentMap;"]
j["fieldRedirects"].setdefault(FAPI + "tag/convention/v2/ConventionalItemTags", {})["getstatic SPEAR_TOOLS:" + TK] = [ITC, "spearTools", "()" + TK]
j["fieldRedirects"].setdefault(FAPI + "tag/convention/v2/ConventionalItemTags", {})["getstatic SHEARS_TOOLS:" + TK] = [ITC, "shearsTools", "()" + TK]
print("fabric api renames: in")
print("batch 4: in")







print("entity/item: adapters in")
print(f"world: {nrt} RenderType factories bridged, entity/block-entity/model adapters in")
print(f"phase 1: {len(owners)} input owners, {len(getters)} shader getters, {len(j['entryHooks'])} entry hooks, {nren} render->extract renames, {len(lines)} dyed constants")
# ---------- phase 2 ----------
def javap_statics(cls):
    out = subprocess.run(["javap", "-s", "-p", "-cp", str(MOD / "build/classes"), cls], capture_output=True, text=True).stdout
    res = {}; name = None
    for ln in out.splitlines():
        m = re.match(r"\s*public static \S+ (\w+)\(", ln)
        if m: name = m.group(1); continue
        m = re.match(r"\s*descriptor: (\S+)", ln)
        if m and name: res.setdefault(name, []).append(shim_desc(m.group(1))); name = None
    return res
if (MOD / "build/classes/foxgrade/shim/GuiCompat.class").exists():
    miss_gg = {ren_desc(m) for m in missing_of("net.minecraft.client.gui.GuiGraphics", "net/minecraft/client/gui/GuiGraphicsExtractor")}
    tbl = {}; unmatched = []
    for name, descs in javap_statics("foxgrade.shim.GuiCompat").items():
        for d in descs:
            if not d.startswith("(" + GGE): continue
            key = name + "(" + d[len("(" + GGE):]
            if key in miss_gg: tbl[key] = [GC, name, d]
            else: unmatched.append(key)
    cr.setdefault("net/minecraft/client/gui/GuiGraphicsExtractor", {}).update(tbl)
    print(f"phase 2: GuiGraphics {len(tbl)} bridged of {len(miss_gg)} missing; shim methods without an old counterpart: {unmatched}")
    print("   still unbridged:", sorted(re.sub(r'L[\w/$]*?([\w$]+);', r'\1', m) for m in miss_gg - set(tbl)))
    miss_rs = {ren_desc(m) for m in missing_of("com.mojang.blaze3d.systems.RenderSystem", "com/mojang/blaze3d/systems/RenderSystem")}
    tbl = {}; unmatched = []
    for name, descs in javap_statics("foxgrade.shim.RenderSystemCompat").items():
        for d in descs:
            key = name + d
            if key in miss_rs: tbl[key] = ["foxgrade/shim/RenderSystemCompat", name, d]
            elif name != "shaderTexture": unmatched.append(key)
    cr.setdefault("com/mojang/blaze3d/systems/RenderSystem", {}).update(tbl)
    print(f"phase 2: RenderSystem {len(tbl)} bridged of {len(miss_rs)} missing; shim methods without an old counterpart: {unmatched}")
# Lambdas are created through invokedynamic whose return type is the ORIGINAL interface name (the class rename
# happens later in the same pass), so SAM renames must be reachable under the old interface name as well.
for _old_c, _new_c in list(j["classRenames"].items()):
    if _new_c in j.get("samRenames", {}) and _old_c not in j["samRenames"]:
        j["samRenames"][_old_c] = dict(j["samRenames"][_new_c])
BR.write_text(json.dumps(j, indent=2)); print("wrote", BR.name)
