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
SHIM_RENAMES = {"foxgrade/shim/TesselatorShim": "com/mojang/blaze3d/vertex/Tesselator", "foxgrade/shim/BufferUploaderShim": "com/mojang/blaze3d/vertex/BufferUploader", "foxgrade/shim/VertexFormatModeShim": "com/mojang/blaze3d/vertex/VertexFormat$Mode"}
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
for holder, typ, tdesc, colls in [("Blocks", "Block", BL, [f.split(":")[0] for f in new["net/minecraft/world/level/block/Blocks"]["f"] if f.startswith("DYED_")]),
                                  ("Items", "Item", IT, [f.split(":")[0] for f in new["net/minecraft/world/item/Items"]["f"] if f.startswith("DYED_")])]:
    owner = "net/minecraft/world/level/block/Blocks" if holder == "Blocks" else "net/minecraft/world/item/Items"
    for coll in colls:
        suffix = coll[len("DYED_"):]
        for up, getter in COLORS:
            fld = up + "_" + suffix; meth = holder + "_" + fld
            lines.append(f"  public static {typ} {meth}() {{ return ({typ}) {holder}.{coll}.{getter}(); }}")
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
    cr["net/minecraft/client/gui/GuiGraphicsExtractor"] = tbl
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
BR.write_text(json.dumps(j, indent=2)); print("wrote", BR.name)
