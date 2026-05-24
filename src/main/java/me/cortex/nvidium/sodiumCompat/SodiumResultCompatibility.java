package me.cortex.nvidium.sodiumCompat;

import it.unimi.dsi.fastutil.longs.LongArrays;
import me.cortex.nvidium.Nvidium;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;
import net.minecraft.client.MinecraftClient;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;

public class SodiumResultCompatibility {

    public static RepackagedSectionOutput repackage(ChunkBuildOutput result) {
        Nvidium.LOGGER.info("[Nvidium] repackage called, meshes: " + result.meshes.size());
        int formatSize = 16;
        int geometryBytes = result.meshes.values().stream().mapToInt(a -> a.getVertexData().getLength()).sum();
        Nvidium.LOGGER.info("[Nvidium] geometryBytes: " + geometryBytes);
        if (geometryBytes == 0) {
            return null;
        }
        var output = new NativeBuffer(geometryBytes);
        var offsets = new short[8];
        var min = new Vector3i(2000);
        var max = new Vector3i(-2000);
        try {
            packageSectionGeometry(formatSize, output, offsets, result, min, max);
        } catch (Exception e) {
            Nvidium.LOGGER.error("[Nvidium] Exception in packageSectionGeometry: ", e);
            output.free();
            return null;
        }
        min.x = Math.max(Math.min(min.x, 15), 0);
        min.y = Math.max(Math.min(min.y, 15), 0);
        min.z = Math.max(Math.min(min.z, 15), 0);
        max.x = Math.max(Math.min(max.x, 16), 0);
        max.y = Math.max(Math.min(max.y, 16), 0);
        max.z = Math.max(Math.min(max.z, 16), 0);
        var size = new Vector3i(
            Math.min(15, Math.max(max.x - min.x - 1, 0)),
            Math.min(15, Math.max(max.y - min.y - 1, 0)),
            Math.min(15, Math.max(max.z - min.z - 1, 0))
        );
        int quadCount = (geometryBytes / formatSize) / 4;
        Nvidium.LOGGER.info("[Nvidium] quadCount: " + quadCount + " min: " + min + " size: " + size);
        return new RepackagedSectionOutput(quadCount, output, offsets, min, size);
    }

    private static void copyQuad(long from, long to) {
        for (long i = 0; i < 64; i += 8) {
            MemoryUtil.memPutLong(to + i, MemoryUtil.memGetLong(from + i));
        }
    }

    private static void packageSectionGeometry(int formatSize, NativeBuffer output, short[] outOffsets, ChunkBuildOutput result, Vector3i min, Vector3i max) {
        int offset = 0;
        long outPtr = MemoryUtil.memAddress(output.getDirectBuffer());
        var cameraPos = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
        float cpx = (float) (cameraPos.x - (result.render.getChunkX() << 4));
        float cpy = (float) (cameraPos.y - (result.render.getChunkY() << 4));
        float cpz = (float) (cameraPos.z - (result.render.getChunkZ() << 4));
        {
            float len = (float) Math.sqrt(cpx * cpx + cpy * cpy + cpz * cpz);
            if (len > 0) {
                cpx *= 1 / len; cpy *= 1 / len; cpz *= 1 / len;
                len = Math.min(len, 32);
                cpx *= len; cpy *= len; cpz *= len;
            }
        }
        var translucentData = result.meshes.get(DefaultTerrainRenderPasses.TRANSLUCENT);
        if (translucentData != null) {
            int quadCount = 0;
            for (int i = 0; i < 7; i++) {
                var part = translucentData.getVertexRanges()[i];
                quadCount += part != null ? part.vertexCount() / 4 : 0;
            }
            int quadId = 0;
            long[] sortingData = new long[quadCount];
            long[] srcs = new long[7];
            for (int i = 0; i < 7; i++) {
                var part = translucentData.getVertexRanges()[i];
                if (part != null) {
                    long src = MemoryUtil.memAddress(translucentData.getVertexData().getDirectBuffer()) + (long) part.vertexStart() * formatSize;
                    srcs[i] = src;
                    float cx = 0, cy = 0, cz = 0;
                    for (int j = 0; j < part.vertexCount(); j++) {
                        long base = src + (long) j * formatSize;
                        MemoryUtil.memPutByte(base + 6L, (byte) 0b100);
                        float x = decodePosition(MemoryUtil.memGetShort(base));
                        float y = decodePosition(MemoryUtil.memGetShort(base + 2));
                        float z = decodePosition(MemoryUtil.memGetShort(base + 4));
                        updateSectionBounds(min, max, x, y, z);
                        cx += x; cy += y; cz += z;
                        if ((j & 3) == 3) {
                            cx *= 0.25f; cy *= 0.25f; cz *= 0.25f;
                            float dx = cx - cpx, dy = cy - cpy, dz = cz - cpz;
                            float dist = dx * dx + dy * dy + dz * dz;
                            int sortDistance = (int) (dist * (1 << 12));
                            sortingData[quadId++] = (((long) sortDistance) << 32) | ((((long) j >> 2) << 3) | i);
                            cx = 0; cy = 0; cz = 0;
                        }
                    }
                }
            }
            if (quadId != sortingData.length) throw new IllegalStateException("quadId mismatch");
            LongArrays.radixSort(sortingData);
            for (int i = 0; i < sortingData.length; i++) {
                long data = sortingData[i];
                copyQuad(srcs[(int)(data&7)] + ((data>>3)&((1L<<29)-1))*4*formatSize, outPtr+((sortingData.length-1)-i)*4L*formatSize);
            }
            offset += quadCount;
        }
        outOffsets[7] = (short) offset;
        var solid = result.meshes.get(DefaultTerrainRenderPasses.SOLID);
        var cutout = result.meshes.get(DefaultTerrainRenderPasses.CUTOUT);
        for (int i = 0; i < 7; i++) {
            int poff = offset;
            if (solid != null) {
                var part = solid.getVertexRanges()[i];
                if (part != null) {
                    long src = MemoryUtil.memAddress(solid.getVertexData().getDirectBuffer()) + (long) part.vertexStart() * formatSize;
                    long dst = outPtr + offset * 4L * formatSize;
                    MemoryUtil.memCopy(src, dst, (long) part.vertexCount() * formatSize);
                    for (int j = 0; j < part.vertexCount(); j++) {
                        long base = dst + (long) j * formatSize;
                        MemoryUtil.memPutByte(base + 6L, (byte) 0b100);
                        updateSectionBounds(min, max, base);
                    }
                    offset += part.vertexCount() / 4;
                }
            }
            if (cutout != null) {
                var part = cutout.getVertexRanges()[i];
                if (part != null) {
                    long src = MemoryUtil.memAddress(cutout.getVertexData().getDirectBuffer()) + (long) part.vertexStart() * formatSize;
                    long dst = outPtr + offset * 4L * formatSize;
                    MemoryUtil.memCopy(src, dst, (long) part.vertexCount() * formatSize);
                    for (int j = 0; j < part.vertexCount(); j++) {
                        long base = dst + (long) j * formatSize;
                        short sflags = MemoryUtil.memGetByte(base + 6L);
                        short mipbits = (short) ((sflags & (3 << 1)) >> 1);
                        if (mipbits == 0b10 && IrisCheck.IRIS_LOADED) mipbits = 0b01;
                        MemoryUtil.memPutByte(base + 6L, (byte) (((sflags & 1) << 2) | mipbits));
                        updateSectionBounds(min, max, base);
                    }
                    offset += part.vertexCount() / 4;
                }
            }
            outOffsets[i] = (short) (offset - poff);
        }
        if (offset * 4 * formatSize != output.getLength()) throw new IllegalStateException("Geometry size mismatch");
    }

    private static float decodePosition(short v) {
        return Short.toUnsignedInt(v) * (1f / 2048.0f) - 8.0f;
    }

    private static void updateSectionBounds(Vector3i min, Vector3i max, long vertex) {
        float x = decodePosition(MemoryUtil.memGetShort(vertex));
        float y = decodePosition(MemoryUtil.memGetShort(vertex + 2));
        float z = decodePosition(MemoryUtil.memGetShort(vertex + 4));
        updateSectionBounds(min, max, x, y, z);
    }

    private static void updateSectionBounds(Vector3i min, Vector3i max, float x, float y, float z) {
        min.x = (int) Math.min(min.x, Math.floor(x));
        min.y = (int) Math.min(min.y, Math.floor(y));
        min.z = (int) Math.min(min.z, Math.floor(z));
        max.x = (int) Math.max(max.x, Math.ceil(x));
        max.y = (int) Math.max(max.y, Math.ceil(y));
        max.z = (int) Math.max(max.z, Math.ceil(z));
    }
}
