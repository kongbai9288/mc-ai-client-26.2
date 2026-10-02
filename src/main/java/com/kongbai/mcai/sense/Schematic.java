package com.kongbai.mcai.sense;

import com.kongbai.mcai.util.Json;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 投影文件（.litematic / .schematic）解析与摘要。
 *
 * <h3>为什么不直接把 NBT 丢给 AI</h3>
 * 一个中等规模的投影展开后是几十万个方块。原始 NBT 或全量方块列表会瞬间
 * 撑爆上下文，而且模型读不完也读不懂。用户要的其实是"这栋楼长什么样、
 * 要用哪些材料、下一步该放哪"——所以这里把 NBT 压成四层摘要：
 * <ol>
 *   <li><b>元信息</b>：尺寸、区域数、总方块数</li>
 *   <li><b>材料清单</b>：每种方块多少个（按数量降序，截断）</li>
 *   <li><b>分层剖面</b>：每层的方块数与非空占比，AI 靠它判断"建到哪了"</li>
 *   <li><b>差异</b>：对比实际世界，列出缺失/放错的方块（这是施工的核心）</li>
 * </ol>
 *
 * <p>格式说明：
 * <ul>
 *   <li>.litematic：顶层 Regions，每个 region 里有 BlockStates（packed long 数组）+ Palette</li>
 *   <li>.schematic（WorldEdit / Sponge）：顶层 Blocks（int 数组或 packed）+ Palette</li>
 * </ul>
 */
public final class Schematic {

    /** 解析结果。 */
    public static final class Data {
        public final String name;
        public final int sizeX, sizeY, sizeZ;
        public final Map<String, Integer> materials;
        public final List<LayerStat> layers;
        public final String sourceFormat;
        /** 区域名 -> 该区域尺寸（litematic 可能多区域）。 */
        public final Map<String, int[]> regions;
        /** 用于差异比对的解码后方块索引（region 名 -> 索引数组），可能为 null（过大时不解码）。 */
        public final Map<String, int[]> decoded;
        public final Map<String, List<String>> palettes;

        Data(String name, int x, int y, int z, Map<String, Integer> materials,
             List<LayerStat> layers, String fmt, Map<String, int[]> regions,
             Map<String, int[]> decoded, Map<String, List<String>> palettes) {
            this.name = name; this.sizeX = x; this.sizeY = y; this.sizeZ = z;
            this.materials = materials; this.layers = layers;
            this.sourceFormat = fmt; this.regions = regions;
            this.decoded = decoded; this.palettes = palettes;
        }
    }

    public static final class LayerStat {
        public final int y;
        public final int count;
        public LayerStat(int y, int count) { this.y = y; this.count = count; }
    }

    private static final int MAX_DECODE = 4_000_000; // 超过就不解码，只给统计

    private Schematic() {}

    /**
     * 解析一个投影文件。
     *
     * @param file .litematic 或 .schematic
     * @return 解析结果；失败会抛异常，由调用方转成工具错误
     */
    public static Data parse(Path file) throws IOException {
        String fn = file.getFileName().toString();
        CompoundTag root;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        } catch (IOException e1) {
            // 部分 .schematic 未压缩
            try (InputStream raw = Files.newInputStream(file)) {
                root = NbtIo.read(new java.io.DataInputStream(raw),
                        NbtAccounter.unlimitedHeap());
            }
        }
        return parse(root, fn);
    }

    /** 从一个已解析的 NBT 根构建摘要（供内存中的投影使用）。 */
    public static Data parse(CompoundTag root, String fallbackName) {
        if (root.contains("Regions")) {
            return parseLitematica(root, fallbackName);
        }
        return parseSchematic(root, fallbackName);
    }

    // ==================================================================
    // .litematic
    // ==================================================================

    private static Data parseLitematica(CompoundTag root, String fn) {
        String name = root.contains("Name") ? root.getString("Name").orElse(fn) : fn;
        CompoundTag regions = root.getCompound("Regions").orElse(new CompoundTag());

        Map<String, int[]> regionSizes = new LinkedHashMap<>();
        Map<String, List<String>> palettes = new LinkedHashMap<>();
        Map<String, int[]> decoded = new LinkedHashMap<>();
        Map<String, Integer> materials = new LinkedHashMap<>();
        List<LayerStat> layerTotals = new ArrayList<>();

        int totalX = 0, totalY = 0, totalZ = 0;
        Map<Integer, Integer> layerAgg = new LinkedHashMap<>();

        for (String rk : regions.keySet()) {
            CompoundTag r = regions.getCompound(rk).orElse(null);
            if (r == null) continue;
            CompoundTag size = r.getCompound("Size").orElse(new CompoundTag());
            int sx = size.getInt("x").orElse(0);
            int sy = size.getInt("y").orElse(0);
            int sz = size.getInt("z").orElse(0);
            regionSizes.put(rk, new int[] {sx, sy, sz});
            totalX = Math.max(totalX, sx);
            totalY = Math.max(totalY, sy);
            totalZ = Math.max(totalZ, sz);

            List<String> palette = readPalette(r.getList("BlockStatePalette").orElse(null));
            palettes.put(rk, palette);

            long[] states = longArray(r.get("BlockStates"));
            int bits = Math.max(4, ceilLog2(Math.max(1, palette.size())));
            long count = (long) sx * sy * sz;

            Map<Integer, Integer> perLayer = new LinkedHashMap<>();
            if (count > 0 && count <= MAX_DECODE && states != null) {
                int[] idx = new int[(int) count];
                decodePacked(states, bits, idx);
                decoded.put(rk, idx);
                for (int i = 0; i < idx.length; i++) {
                    int y = indexToY(i, sx, sy, sz);
                    String block = idx[i] < palette.size() ? palette.get(idx[i]) : "unknown";
                    if (isAir(block)) continue;
                    materials.merge(block, 1, Integer::sum);
                    perLayer.merge(y, 1, Integer::sum);
                }
            } else {
                // 太大：只做基于 packed 数据的粗略统计（不展开）
                for (int i = 0; i < count && states != null; i++) {
                    int v = (int) readPacked(states, i, bits);
                    String block = v < palette.size() ? palette.get(v) : "unknown";
                    if (isAir(block)) continue;
                    materials.merge(block, 1, Integer::sum);
                }
            }
            perLayer.forEach((y, c) -> layerAgg.merge(y, c, Integer::sum));
        }

        layerAgg.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> layerTotals.add(new LayerStat(e.getKey(), e.getValue())));

        return new Data(name, totalX, totalY, totalZ,
                SenseCache.topN(materials, 20), layerTotals, "litematic",
                regionSizes, decoded, palettes);
    }

    // ==================================================================
    // .schematic (Sponge / WorldEdit)
    // ==================================================================

    private static Data parseSchematic(CompoundTag root, String fn) {
        int sx, sy, sz;
        if (root.contains("Width")) {
            sx = root.getShort("Width").orElse((short) 0);
            sy = root.getShort("Height").orElse((short) 0);
            sz = root.getShort("Length").orElse((short) 0);
        } else {
            CompoundTag size = root.getCompound("size").orElse(new CompoundTag());
            sx = size.getInt("x").orElse(0);
            sy = size.getInt("y").orElse(0);
            sz = size.getInt("z").orElse(0);
        }

        List<String> palette = new ArrayList<>();
        CompoundTag p = null;
        if (root.contains("Palette")) {
            p = root.getCompound("Palette").orElse(null);
        } else if (root.contains("palette")) {
            p = root.getCompound("palette").orElse(null);
        }
        if (p != null) {
            for (String k : p.keySet()) {
                palette.add(null); // 占位，下面按 int 值填
            }
            // Palette 是 name -> int index
            List<String> tmp = new ArrayList<>(p.keySet());
            for (String k : tmp) {
                int i = p.getInt(k).orElse(0);
                while (palette.size() <= i) palette.add("unknown");
                palette.set(i, k);
            }
        }

        long[] blocks = null;
        if (root.contains("BlockData")) {
            blocks = toLongArray(root.getByteArray("BlockData").orElse(new byte[0]));
        } else if (root.contains("BlockStates")) {
            blocks = longArray(root.get("BlockStates"));
        }

        Map<String, Integer> materials = new LinkedHashMap<>();
        Map<Integer, Integer> layerAgg = new LinkedHashMap<>();
        Map<String, int[]> decoded = new LinkedHashMap<>();
        long count = (long) sx * sy * sz;

        if (blocks != null && count > 0 && count <= MAX_DECODE) {
            int[] idx = new int[(int) count];
            // Sponge 用 varint 编码的 byte 数组；这里按每字节处理（覆盖绝大多数情况）
            for (int i = 0; i < idx.length && i < blocks.length * 8; i++) {
                idx[i] = (int) ((blocks[i / 8] >>> ((i % 8) * 8)) & 0xFF);
            }
            decoded.put("main", idx);
            for (int i = 0; i < idx.length; i++) {
                int y = indexToY(i, sx, sy, sz);
                String block = idx[i] < palette.size() ? palette.get(idx[i]) : "unknown";
                if (isAir(block)) continue;
                materials.merge(block, 1, Integer::sum);
                layerAgg.merge(y, 1, Integer::sum);
            }
        }

        List<LayerStat> layers = new ArrayList<>();
        layerAgg.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> layers.add(new LayerStat(e.getKey(), e.getValue())));

        Map<String, int[]> regions = new LinkedHashMap<>();
        regions.put("main", new int[] {sx, sy, sz});
        Map<String, List<String>> pals = new LinkedHashMap<>();
        pals.put("main", palette);
        return new Data(fn, sx, sy, sz, SenseCache.topN(materials, 20), layers,
                "schematic", regions, decoded, pals);
    }

    // ==================================================================
    // 摘要输出（给 AI 的部分）
    // ==================================================================

    /**
     * 生成 AI 可读摘要。
     *
     * @param maxLayers 最多输出多少层剖面（避免长上下文）
     */
    public static Json.Obj summarize(Data d, int maxLayers) {
        Json.Obj o = Json.obj();
        o.put("ok", true);
        o.put("name", d.name);
        o.put("format", d.sourceFormat);
        o.put("size", Json.obj().put("x", d.sizeX).put("y", d.sizeY).put("z", d.sizeZ));
        o.put("total_blocks", d.materials.values().stream().mapToInt(Integer::intValue).sum());

        Json.Arr mats = Json.arr();
        d.materials.forEach((k, v) -> mats.add(Json.obj().put("block", k).put("count", v)));
        o.put("materials", mats);

        Json.Arr layers = Json.arr();
        int n = Math.min(maxLayers, d.layers.size());
        for (int i = 0; i < n; i++) {
            LayerStat ls = d.layers.get(i);
            layers.add(Json.obj().put("y", ls.y).put("blocks", ls.count));
        }
        o.put("layer_profile", layers);
        o.put("layer_count", d.layers.size());
        if (d.layers.size() > n) {
            o.put("layer_truncated", d.layers.size() - n);
        }

        Json.Arr rs = Json.arr();
        d.regions.forEach((k, v) -> rs.add(Json.obj()
                .put("name", k).put("x", v[0]).put("y", v[1]).put("z", v[2])));
        o.put("regions", rs);
        o.put("hint", "materials 是材料清单；layer_profile 是分层剖面，"
                + "施工时配合 diff 工具定位当前该建哪一层");
        return o;
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    private static List<String> readPalette(ListTag list) {
        List<String> out = new ArrayList<>();
        if (list == null) return out;
        for (int i = 0; i < list.size(); i++) {
            Tag t = list.get(i);
            if (t instanceof CompoundTag c) {
                String n = c.getString("Name").orElse("unknown");
                out.add(n);
            } else {
                out.add("unknown");
            }
        }
        return out;
    }

    private static long[] longArray(Tag t) {
        if (t instanceof net.minecraft.nbt.LongArrayTag la) {
            return la.getAsLongArray();
        }
        if (t instanceof net.minecraft.nbt.IntArrayTag ia) {
            int[] src = ia.getAsIntArray();
            long[] out = new long[src.length];
            for (int i = 0; i < src.length; i++) out[i] = src[i];
            return out;
        }
        return null;
    }

    private static long[] toLongArray(byte[] b) {
        int n = (b.length + 7) / 8;
        long[] out = new long[n];
        for (int i = 0; i < b.length; i++) {
            out[i / 8] |= ((long) (b[i] & 0xFF)) << ((i % 8) * 8);
        }
        return out;
    }

    /** 把 packed long 数组解码成索引。 */
    private static void decodePacked(long[] data, int bits, int[] out) {
        long mask = (1L << bits) - 1L;
        for (int i = 0; i < out.length; i++) {
            long bitIndex = (long) i * bits;
            int arrIdx = (int) (bitIndex >>> 6);
            int bitOff = (int) (bitIndex & 63);
            long v = data[arrIdx] >>> bitOff;
            if (bitOff + bits > 64 && arrIdx + 1 < data.length) {
                v |= data[arrIdx + 1] << (64 - bitOff);
            }
            out[i] = (int) (v & mask);
        }
    }

    private static long readPacked(long[] data, long index, int bits) {
        long mask = (1L << bits) - 1L;
        long bitIndex = index * bits;
        int arrIdx = (int) (bitIndex >>> 6);
        if (arrIdx >= data.length) return 0;
        int bitOff = (int) (bitIndex & 63);
        long v = data[arrIdx] >>> bitOff;
        if (bitOff + bits > 64 && arrIdx + 1 < data.length) {
            v |= data[arrIdx + 1] << (64 - bitOff);
        }
        return v & mask;
    }

    /** litematic 的索引顺序是 x 最快、z 次之、y 最慢（YZX 变体，以 x 为最内层）。 */
    private static int indexToY(int i, int sx, int sy, int sz) {
        int area = sx * sz;
        return area == 0 ? 0 : i / area;
    }

    private static int ceilLog2(int n) {
        int r = 0;
        while ((1 << r) < n) r++;
        return r;
    }

    private static boolean isAir(String block) {
        return block == null || block.endsWith(":air") || block.equals("minecraft:air")
                || block.equals("air") || block.endsWith(":cave_air")
                || block.endsWith(":void_air");
    }
}
