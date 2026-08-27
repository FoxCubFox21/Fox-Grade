#!/usr/bin/env node
// RENDERING AUDIT — Blaze3D moved rendering out of net.minecraft.* into com.mojang.blaze3d.*.
// That crosses the package ROOT, which our shared-domain safety guard deliberately rejects, so
// rendering is exactly where coverage is most likely to be weak. Check it explicitly.
import fs from 'node:fs';
import { spawnSync } from 'node:child_process';

const cp = fs.readFileSync('/tmp/foxgrade_cp.txt', 'utf8').trim();
const real = new Set();
for (const jar of cp.split(':')) {
  if (!jar.endsWith('.jar') || !fs.existsSync(jar)) continue;
  const r = spawnSync('unzip', ['-Z1', jar, '*.class'], { encoding: 'utf8', maxBuffer: 64e6 });
  if (r.status !== 0 || !r.stdout) continue;
  for (const l of r.stdout.split('\n')) { const p = l.trim();
    if (p.endsWith('.class') && !p.includes('$')) real.add(p.slice(0, -6).replace(/\//g, '.')); }
}
const d = JSON.parse(fs.readFileSync('rules.json', 'utf8'));
const flat = new Map();
for (const [k, v] of Object.entries(d)) { if (k.startsWith('_')) continue;
  for (const r of (v.renames || [])) if (r.verified !== false && !flat.has(r.fromFqcn)) flat.set(r.fromFqcn, r.toFqcn); }
const resolve = (fq) => { let c = fq, s = new Set(); while (flat.has(c) && !s.has(c)) { s.add(c); c = flat.get(c); } return c; };

// The rendering classes an old mod actually imports, across eras (MCP 1.12/1.16 and mojmap 1.16-1.20).
const RENDER = [
  'net.minecraft.client.renderer.GlStateManager',
  'com.mojang.blaze3d.platform.GlStateManager',
  'com.mojang.blaze3d.matrix.MatrixStack',
  'com.mojang.blaze3d.vertex.PoseStack',
  'net.minecraft.client.renderer.Tessellator',
  'com.mojang.blaze3d.vertex.Tesselator',
  'net.minecraft.client.renderer.BufferBuilder',
  'com.mojang.blaze3d.vertex.BufferBuilder',
  'net.minecraft.client.renderer.vertex.VertexFormat',
  'com.mojang.blaze3d.vertex.VertexFormat',
  'net.minecraft.client.renderer.IRenderTypeBuffer',
  'net.minecraft.client.renderer.MultiBufferSource',
  'com.mojang.blaze3d.vertex.VertexConsumer',
  'net.minecraft.client.renderer.IVertexBuilder',
  'com.mojang.blaze3d.systems.RenderSystem',
  'net.minecraft.client.renderer.RenderType',
  'net.minecraft.client.renderer.texture.TextureManager',
  'net.minecraft.client.renderer.entity.EntityRenderer',
  'net.minecraft.client.renderer.tileentity.TileEntityRenderer',
  'net.minecraft.client.renderer.blockentity.BlockEntityRenderer',
  'net.minecraft.client.renderer.model.IBakedModel',
  'net.minecraft.client.resources.model.BakedModel',
  'net.minecraft.client.gui.AbstractGui',
  'net.minecraft.client.gui.GuiComponent',
  'com.mojang.blaze3d.platform.Lighting',
  'net.minecraft.client.renderer.RenderHelper',
];
let ok = 0, fixed = 0, miss = 0;
const rows = [];
for (const c of RENDER) {
  if (real.has(c)) { ok++; rows.push(['VALID ', c, '']); continue; }
  const t = resolve(c);
  if (t !== c && real.has(t)) { fixed++; rows.push(['FIXED ', c, '-> ' + t]); }
  else { miss++; rows.push(['MISS  ', c, '']); }
}
for (const [s, a, b] of rows) console.log(`  ${s} ${a}${b ? '\n           ' + b : ''}`);
console.log(`\n  already valid: ${ok} · fixed by rules: ${fixed} · UNRESOLVED: ${miss}  (of ${RENDER.length})`);

// What DOES exist in 26.2 under blaze3d, so we can see where things actually went?
const blaze = [...real].filter((c) => c.startsWith('com.mojang.blaze3d.')).sort();
console.log(`\n  com.mojang.blaze3d.* classes present in 26.2: ${blaze.length}`);
for (const c of blaze.slice(0, 18)) console.log('    ' + c);
