// Rewrite class + method + field references inside a Mixin refmap.json.
//
// Refmap entries are compact selectors of two shapes:
//   method:  L<owner>;<methodName>(<paramDesc>)<returnDesc>
//   field:   L<owner>;<fieldName>:<typeDesc>
// Plus bare owner strings ("net/minecraft/class_310") that some tools emit.
//
// A CLASS-ONLY remap leaves the {name} intact. That's what broke the appleskin port before —
// `Minecraft.method_1574()V` translated the owner but not the intermediary method name, and
// the mixin injection then failed to find any target. This version parses each entry into
// (owner, name, desc), translates each piece through the bridge, and reassembles.
package foxgrade;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MixinRefmapRemapper {
  private static final Pattern BARE_OWNER = Pattern.compile("^[\\w$]+(?:/[\\w$]+)+$");

  public static final class Result {
    public final JsonElement json; public final int hits;
    Result(JsonElement j, int h) { json = j; hits = h; }
  }

  public static Result rewrite(JsonElement input, IntermediaryBridge bridge, Map<String, String> rulesClassTable, FabricApiBridges apiBridges) {
    Map<String, String> mergedClasses = new java.util.HashMap<>(bridge.size() + rulesClassTable.size());
    mergedClasses.putAll(bridge.classTable());
    mergedClasses.putAll(rulesClassTable);
    mergedClasses.putAll(apiBridges.classRenames());
    Walker w = new Walker(mergedClasses, bridge.methodTable(), bridge.fieldTable(),
        bridge.globalMethodTable(), bridge.globalFieldTable(), apiBridges.renames(),
        bridge.mojangMethodTable(), bridge.mojangMethodGlobalTable(),
        bridge.yarnMethodTable(), bridge.yarnFieldTable());
    JsonElement out = w.walk(input);
    return new Result(out, w.hits);
  }

  private static final class Walker {
    final Map<String, String> classes;
    final Map<String, Map<String, String>> methods;
    final Map<String, Map<String, String>> fields;
    final Map<String, String> globalMethods;
    final Map<String, String> globalFields;
    final Map<String, Map<String, String>> apiRenames;
    final Map<String, Map<String, String>> mojangMethods;
    final Map<String, String> mojangMethodsGlobal;
    final Map<String, Map<String, String>> yarnMethods;
    final Map<String, Map<String, String>> yarnFields;
    int hits = 0;
    Walker(Map<String, String> c, Map<String, Map<String, String>> m, Map<String, Map<String, String>> f,
           Map<String, String> gm, Map<String, String> gf, Map<String, Map<String, String>> api,
           Map<String, Map<String, String>> mojm, Map<String, String> mojg,
           Map<String, Map<String, String>> ym, Map<String, Map<String, String>> yf) {
      classes = c; methods = m; fields = f; globalMethods = gm; globalFields = gf; apiRenames = api;
      mojangMethods = mojm; mojangMethodsGlobal = mojg; yarnMethods = ym; yarnFields = yf;
    }

    JsonElement walk(JsonElement v) {
      if (v == null || v.isJsonNull()) return v;
      if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
        String s = v.getAsString();
        if (BARE_OWNER.matcher(s).matches() && classes.containsKey(s)) { hits++; return new JsonPrimitive(classes.get(s)); }
        if (s.startsWith("L") && s.contains(";")) {
          String r = rewriteSelector(s);
          if (!r.equals(s)) { hits++; return new JsonPrimitive(r); }
        }
        // Bare member selectors (no owner): "method_5751()F" or "field_1687:Lnet/minecraft/class_638;".
        // These appear in refmap `data` sections and back @Shadow member resolution — leave them
        // untranslated and every shadow member fails lookup at apply time. Intermediary ids are
        // globally unique, so the owner-less global tables resolve them safely.
        int paren = s.indexOf('(');
        if (paren > 0 && !s.startsWith("L") && !s.contains(";" ) || (paren > 0 && s.indexOf(';') > paren)) {
          String name = s.substring(0, paren);
          String desc = s.substring(paren);
          String to = globalMethods.get(name);
          if (to == null) to = mojangMethodsGlobal.get(name + desc);
          String newDesc = remapAllTypes(desc);
          if (to != null || !newDesc.equals(desc)) {
            hits++;
            return new JsonPrimitive((to != null ? to : name) + newDesc);
          }
          return v;
        }
        int colon = s.indexOf(':');
        if (colon > 0 && !s.startsWith("L") && s.indexOf('(') < 0) {
          String name = s.substring(0, colon);
          String type = s.substring(colon + 1);
          String to = globalFields.get(name);
          String newType = remapAllTypes(type);
          if (to != null || !newType.equals(type)) {
            hits++;
            return new JsonPrimitive((to != null ? to : name) + ":" + newType);
          }
          return v;
        }
        // Descriptors that don't lead with L (e.g. bare method desc like "(I)V") — walk types only.
        if (s.contains(";")) {
          String r = remapAllTypes(s);
          if (!r.equals(s)) { hits++; return new JsonPrimitive(r); }
        }
        return v;
      }
      if (v.isJsonArray()) {
        JsonArray arr = new JsonArray();
        for (var e : v.getAsJsonArray()) arr.add(walk(e));
        return arr;
      }
      if (v.isJsonObject()) {
        JsonObject o = new JsonObject();
        for (var e : v.getAsJsonObject().entrySet()) o.add(e.getKey(), walk(e.getValue()));
        return o;
      }
      return v;
    }

    // Parse Lowner;name(paramDesc)ret  OR  Lowner;name:typeDesc  → rewrite each piece.
    String rewriteSelector(String s) {
      // Find the terminating ';' of the owner
      int semi = s.indexOf(';');
      if (semi < 2 || s.charAt(0) != 'L') return s;
      String owner = s.substring(1, semi);
      String remainder = s.substring(semi + 1);
      String newOwner = classes.getOrDefault(owner, owner);
      // Fallback for inner classes: rewrite the outer if the full lookup misses
      if (newOwner.equals(owner)) {
        int d = owner.indexOf('$');
        if (d >= 0) {
          String outer = owner.substring(0, d);
          String outerNew = classes.get(outer);
          if (outerNew != null) newOwner = outerNew + owner.substring(d);
        }
      }
      // method: name(params)ret
      int paren = remainder.indexOf('(');
      if (paren >= 0) {
        String name = remainder.substring(0, paren);
        String desc = remainder.substring(paren);       // "(...)..." — still in ORIGINAL names here
        String newName = lookupMemberName(methods, owner, name, globalMethods);
        // Mojang-source rename (name+original-descriptor key) wins if the plain lookup was identity.
        if (newName.equals(name)) {
          Map<String, String> moj = mojangMethods.get(owner);
          String to = moj != null ? moj.get(name + desc) : null;
          if (to == null) { Map<String, String> ym = yarnMethods.get(owner); if (ym != null) to = ym.get(name + desc); }
          if (to == null && (owner.startsWith("net/minecraft/") || owner.startsWith("com/mojang/")))
            to = mojangMethodsGlobal.get(name + desc);
          if (to != null) newName = to;
        }
        String newDesc = remapAllTypes(desc);
        return "L" + newOwner + ";" + newName + newDesc;
      }
      // field: name:type
      int colon = remainder.indexOf(':');
      if (colon >= 0) {
        String name = remainder.substring(0, colon);
        String type = remainder.substring(colon + 1);
        String newName = lookupMemberName(fields, owner, name, globalFields);
        String newType = remapAllTypes(type);
        return "L" + newOwner + ";" + newName + ":" + newType;
      }
      // bare L…; type — just the type.
      return "L" + newOwner + ";" + remapAllTypes(remainder);
    }

    String lookupMemberName(Map<String, Map<String, String>> table, String owner, String name, Map<String, String> global) {
      Map<String, String> m = table.get(owner);
      if (m != null) { String to = m.get(name); if (to != null) return to; }
      int d = owner.indexOf('$');
      if (d >= 0) {
        Map<String, String> m2 = table.get(owner.substring(0, d));
        if (m2 != null) { String to = m2.get(name); if (to != null) return to; }
      }
      // API-level rename bridge (for methods only — the caller passes the methods table for methods,
      // fields for fields; apiRenames covers methods, so only consult when table == methods).
      if (table == methods) {
        Map<String, String> api = apiRenames.get(owner);
        if (api != null) { String to = api.get(name); if (to != null) return to; }
      }
      // The intermediary global tables (method_XXXX ids) are unambiguous — apply for any owner.
      String g = global.get(name);
      if (g != null) return g;
      return name;
    }

    String remapAllTypes(String desc) {
      Matcher m = Pattern.compile("L([\\w/$]+);").matcher(desc);
      StringBuilder sb = new StringBuilder();
      int last = 0;
      while (m.find()) {
        sb.append(desc, last, m.start());
        String type = m.group(1);
        String newType = classes.get(type);
        if (newType == null) {
          int d = type.indexOf('$');
          if (d >= 0) {
            String outerNew = classes.get(type.substring(0, d));
            if (outerNew != null) newType = outerNew + type.substring(d);
          }
        }
        sb.append("L").append(newType == null ? type : newType).append(";");
        last = m.end();
      }
      sb.append(desc, last, desc.length());
      return sb.toString();
    }
  }
}
