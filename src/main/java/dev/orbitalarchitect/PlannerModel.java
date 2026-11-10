package dev.orbitalarchitect;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class PlannerModel implements AutoCloseable {
    private record Point(float x, float y, float depth) {}
    private record Face(Point a, Point b, Point c, Point d, int color, Pixels texture, float[] uv) {
        float depth() { return (a.depth + b.depth + c.depth + d.depth) / 4; }
    }
    private record Pixels(int width, int height, int[] colors) {
        static Pixels read(TextureAtlasSprite sprite) {
            int sourceWidth = sprite.contents().width();
            int sourceHeight = sprite.contents().height();
            int width = Math.min(sourceWidth, 64);
            int height = Math.min(sourceHeight, 64);
            var colors = new int[width * height];
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int abgr = sprite.getPixelRGBA(0, x * sourceWidth / width, y * sourceHeight / height);
                colors[y * width + x] = (abgr & 0xFF00FF00) | (abgr & 255) << 16 | (abgr >>> 16 & 255);
            }
            return new Pixels(width, height, colors);
        }

        int at(float u, float v) {
            int x = Math.clamp((int) (u * width), 0, width - 1);
            int y = Math.clamp((int) (v * height), 0, height - 1);
            return colors[y * width + x];
        }
    }

    private final List<Face> faces = new ArrayList<>();
    private float minX = Float.POSITIVE_INFINITY;
    private float minY = Float.POSITIVE_INFINITY;
    private float maxX = Float.NEGATIVE_INFINITY;
    private float maxY = Float.NEGATIVE_INFINITY;
    private final BlockPos origin;
    private final int rotation;
    private final Direction.Axis sectionAxis;
    private final int section, deck;
    private final boolean cutaway, cutPositive;
    private ResourceLocation texture;
    private CompletableFuture<int[]> raster;
    private long uploadedAt;
    private int rasterWidth;
    private int rasterHeight;
    private Point dockMarker;
    private Point galleryMarker;
    private Point problemMarker;
    private final Map<Direction, Point> sockets = new EnumMap<>(Direction.class);
    private final List<SiteMarker> site = new ArrayList<>();
    private record SiteMarker(Point position, int color) {}
    private record Projection(float scale, float x, float y) {}

    PlannerModel(CompoundTag data, int rotation) {
        this(data, rotation, false);
    }

    PlannerModel(CompoundTag data, int rotation, boolean cutaway) {
        this(data, rotation, cutaway, false);
    }

    PlannerModel(CompoundTag data, int rotation, boolean cutaway, boolean survey) {
        this.rotation = rotation;
        this.cutaway = cutaway;
        origin = BlockPos.of(data.getLong("Min"));
        var max = BlockPos.of(data.getLong("Max"));
        var ports = data.getCompound("Ports");
        deck = Direction.Plane.HORIZONTAL.stream().filter(face -> ports.contains(face.getSerializedName()))
                .mapToInt(face -> BlockPos.of(ports.getLong(face.getSerializedName())).getY() - 1).findFirst()
                .orElse(origin.getY() + 4);
        sectionAxis = max.getX() - origin.getX() < max.getZ() - origin.getZ() ? Direction.Axis.X : Direction.Axis.Z;
        cutPositive = sectionAxis == Direction.Axis.X ? rotation < 2 : rotation == 0 || rotation == 3;
        section = (sectionAxis.choose(origin.getX(), origin.getY(), origin.getZ())
                + sectionAxis.choose(max.getX(), max.getY(), max.getZ())) / 2 + (cutPositive ? 2 : -2);
        int ceiling = cutaway ? deck + 3 : Integer.MAX_VALUE;
        if (!cutaway) addGeometry(data.getLongArray("BuiltGhost"), ceiling, 0x475C70, 0x293B50, 0x34485F);
        addGeometry(data.getLongArray("OriginGhost"), ceiling, 0x738A9B, 0x42546C, 0x556B81);
        if (data.contains("AnchorMin")) {
            addBox(BlockPos.of(data.getLong("AnchorMin")), BlockPos.of(data.getLong("AnchorMax")),
                    0x586D83, 0x384D64, 0x425971);
        }
        if (!addMaterials(data, ceiling)) {
            addGeometry(data.getLongArray("Ghost"), ceiling, 0x76EEE6, 0x347E9B, 0x48A9BA);
        }
        for (String key : ports.getAllKeys()) {
            var pos = BlockPos.of(ports.getLong(key));
            addBox(pos, pos.offset(1, 1, 1), 0xB1FFAC, 0x4AAA6B, 0x73D48C);
        }
        if (data.contains("Dock")) {
            var pos = BlockPos.of(data.getLong("Dock"));
            addBox(pos, pos.offset(1, 2, 1), 0xFFF0A3, 0xC48A35, 0xE4AC4D);
            dockMarker = project(pos.getX(), pos.getY() + 1, pos.getZ());
        }
        long[] link = data.getLongArray("GalleryLink");
        if (link.length == 2) {
            var a = BlockPos.of(link[0]);
            var b = BlockPos.of(link[1]);
            for (var pos : List.of(a, b)) addBox(pos, pos.offset(1, 1, 1), 0xE2B6FF, 0x8C55C6, 0xAE78E4);
            galleryMarker = project((a.getX() + b.getX() + 1) * .5f, a.getY() + .5f,
                    (a.getZ() + b.getZ() + 1) * .5f);
        }
        if (data.contains("Blocked")) {
            var pos = BlockPos.of(data.getLong("Blocked"));
            addBox(pos, pos.offset(1, 1, 1), 0xFF998F, 0xA92F32, 0xE95754);
            problemMarker = project(pos.getX() + .5f, pos.getY() + .5f, pos.getZ() + .5f);
        }
        var anchors = data.getCompound("AnchorPorts");
        for (var direction : Direction.values()) {
            var key = direction.getSerializedName();
            if (anchors.contains(key)) sockets.put(direction, center(BlockPos.of(anchors.getLong(key))));
        }
        if (survey) {
            addSite(data.getLongArray("SiteCut"), 0xFFFFBC66);
            addSite(data.getLongArray("SiteProtected"), 0xFFFF6965);
            addSite(data.getLongArray("SiteSupports"), 0xFF71DDEA);
            addSite(data.getLongArray("SiteRepair"), 0xFF9CF59D);
        }
        faces.sort(Comparator.comparingDouble(Face::depth));
    }

    private Point center(BlockPos pos) { return project(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5); }

    private boolean removedBySection(BlockPos pos) {
        if (!cutaway || pos.getY() < deck - 3) return false;
        int coordinate = sectionAxis.choose(pos.getX(), pos.getY(), pos.getZ());
        return cutPositive ? coordinate >= section : coordinate < section;
    }

    private void addSite(long[] positions, int color) {
        for (long position : positions) site.add(new SiteMarker(center(BlockPos.of(position)), color));
    }

    Direction socketAt(double x, double y, int width, int height) {
        if (sockets.isEmpty()) return null;
        var view = projection(width, height);
        Direction nearest = null;
        double distance = 49;
        for (var entry : sockets.entrySet()) {
            var point = entry.getValue();
            double dx = x - (view.x + point.x * view.scale), dy = y - (view.y + point.y * view.scale);
            double candidate = dx * dx + dy * dy;
            if (candidate < distance) {
                nearest = entry.getKey();
                distance = candidate;
            }
        }
        return nearest;
    }

    private boolean addMaterials(CompoundTag data, int ceiling) {
        var blocks = new HashMap<BlockPos, BlockState>();
        var registry = BuiltInRegistries.BLOCK.asLookup();
        var cladding = data.contains("DoorCladding")
                ? AirlockRenderer.claddingSprite(NbtUtils.readBlockState(registry, data.getCompound("DoorCladding"))) : null;
        for (var value : data.getList("Materials", Tag.TAG_COMPOUND)) {
            var group = (CompoundTag) value;
            var state = NbtUtils.readBlockState(registry, group.getCompound("State"));
            if (state.isAir()) continue;
            var boxes = group.getLongArray("Boxes");
            for (int i = 1; i < boxes.length; i += 2) {
                var start = BlockPos.of(boxes[i - 1]);
                var end = BlockPos.of(boxes[i]);
                int top = Math.min(end.getY(), ceiling);
                if (top <= start.getY()) continue;
                if (end.getX() <= start.getX() || end.getZ() <= start.getZ()) continue;
                long size = (long) (end.getX() - start.getX()) * (end.getZ() - start.getZ()) * (top - start.getY());
                if (size > 24_000 - blocks.size()) return false;
                for (var pos : BlockPos.betweenClosed(start,
                        new BlockPos(end.getX() - 1, top - 1, end.getZ() - 1))) {
                    if (!removedBySection(pos)) blocks.put(pos.immutable(), state);
                }
            }
        }
        if (blocks.isEmpty()) return false;

        var minecraft = Minecraft.getInstance();
        var textureCache = new HashMap<TextureAtlasSprite, Pixels>();
        Direction sideX = rotation == 0 || rotation == 1 ? Direction.EAST : Direction.WEST;
        Direction sideZ = rotation == 0 || rotation == 3 ? Direction.SOUTH : Direction.NORTH;
        var directions = List.of(Direction.UP, sideX, sideZ);
        var random = RandomSource.create();
        var unavailable = new HashSet<BlockState>();
        int previous = faces.size();
        blocks.forEach((pos, state) -> {
            if (unavailable.contains(state)) {
                addBox(pos, pos.offset(1, 1, 1), 0x7893A1, 0x49606E, 0x566F7E);
                return;
            }
            int start = faces.size();
            try {
                var model = minecraft.getBlockRenderer().getBlockModel(state);
                long seed = state.getSeed(pos);
                for (var direction : directions) {
                    var adjacent = blocks.get(pos.relative(direction));
                    if (adjacent != null && (state.skipRendering(adjacent, direction)
                            || adjacent.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))) continue;
                    random.setSeed(seed);
                    for (var quad : model.getQuads(state, direction, random, ModelData.EMPTY, null)) {
                        addQuad(pos, state, quad, textureCache, cladding);
                    }
                }
                random.setSeed(seed);
                for (var quad : model.getQuads(state, null, random, ModelData.EMPTY, null)) {
                    if (directions.contains(quad.getDirection())) addQuad(pos, state, quad, textureCache, cladding);
                }
            } catch (RuntimeException requiresWorld) {
                faces.subList(start, faces.size()).clear();
                unavailable.add(state);
                addBox(pos, pos.offset(1, 1, 1), 0x7893A1, 0x49606E, 0x566F7E);
            }
        });
        return faces.size() > previous;
    }

    private void addQuad(BlockPos pos, BlockState state, BakedQuad quad, Map<TextureAtlasSprite, Pixels> cache,
                         TextureAtlasSprite cladding) {
        int[] vertices = quad.getVertices();
        int stride = vertices.length / 4;
        if (stride < 6) return;
        var sprite = quad.getSprite();
        var points = new Point[4];
        var uv = new float[8];
        for (int i = 0; i < 4; i++) {
            int vertex = i * stride;
            points[i] = project((double) pos.getX() + Float.intBitsToFloat(vertices[vertex]),
                    (double) pos.getY() + Float.intBitsToFloat(vertices[vertex + 1]),
                    (double) pos.getZ() + Float.intBitsToFloat(vertices[vertex + 2]));
            uv[2 * i] = (Float.intBitsToFloat(vertices[vertex + 4]) - sprite.getU0()) / (sprite.getU1() - sprite.getU0());
            uv[2 * i + 1] = (Float.intBitsToFloat(vertices[vertex + 5]) - sprite.getV0()) / (sprite.getV1() - sprite.getV0());
        }
        int tint = -1;
        if (quad.isTinted()) {
            try {
                tint = Minecraft.getInstance().getBlockColors().getColor(state, null, null, quad.getTintIndex());
            } catch (RuntimeException requiresWorld) {
                // Some modded tint providers require a live block position.
            }
        }
        float shade = switch (quad.getDirection().getAxis()) { case Y -> 1f; case X -> .76f; case Z -> .87f; };
        int color = color(tint, shade);
        var sample = sprite;
        if (state.getBlock() instanceof AirlockBlock door && sprite.contents().name().equals(AirlockRenderer.LEAF_SPRITE)) {
            if (cladding != null) {
                sample = cladding;
            } else {
                int size = door.size();
                int column = state.getValue(AirlockBlock.COLUMN);
                int row = size - 1 - state.getValue(AirlockBlock.ROW);
                if (quad.getDirection() == Direction.NORTH || quad.getDirection() == Direction.EAST) {
                    column = size - 1 - column;
                }
                for (int i = 0; i < uv.length; i += 2) {
                    uv[i] = (uv[i] + column) / size;
                    uv[i + 1] = (uv[i + 1] + row) / size;
                }
            }
        }
        faces.add(new Face(points[0], points[1], points[2], points[3], color, cache.computeIfAbsent(sample, Pixels::read), uv));
    }

    private static int color(int rgb, float shade) {
        return (int) ((rgb >>> 16 & 255) * shade) << 16 | (int) ((rgb >>> 8 & 255) * shade) << 8
                | (int) ((rgb & 255) * shade);
    }

    private void addGeometry(long[] geometry, int ceiling, int top, int left, int right) {
        for (int i = 1; i < geometry.length; i += 2) {
            var start = BlockPos.of(geometry[i - 1]);
            var end = BlockPos.of(geometry[i]);
            if (start.getY() >= ceiling) continue;
            if (end.getY() > ceiling) end = new BlockPos(end.getX(), ceiling, end.getZ());
            if (cutaway && end.getY() > deck - 3) {
                if (start.getY() < deck - 3) {
                    addBox(start, new BlockPos(end.getX(), deck - 3, end.getZ()), top, left, right);
                    start = new BlockPos(start.getX(), deck - 3, start.getZ());
                }
                if (sectionAxis == Direction.Axis.X) {
                    if (cutPositive) end = new BlockPos(Math.min(end.getX(), section), end.getY(), end.getZ());
                    else start = new BlockPos(Math.max(start.getX(), section), start.getY(), start.getZ());
                } else if (cutPositive) end = new BlockPos(end.getX(), end.getY(), Math.min(end.getZ(), section));
                else start = new BlockPos(start.getX(), start.getY(), Math.max(start.getZ(), section));
                if (start.getX() >= end.getX() || start.getZ() >= end.getZ()) continue;
            }
            addBox(start, end, top, left, right);
        }
    }

    private void addBox(BlockPos start, BlockPos end, int top, int left, int right) {
        int x = start.getX(), y = start.getY(), z = start.getZ();
        int X = end.getX(), Y = end.getY(), Z = end.getZ();
        Point a = project(x, Y, z), b = project(X, Y, z);
        Point c = project(X, Y, Z), d = project(x, Y, Z);
        faces.add(new Face(a, b, c, d, top, null, null));
        if (rotation == 0 || rotation == 1) {
            faces.add(new Face(b, c, project(X, y, Z), project(X, y, z), right, null, null));
        } else {
            faces.add(new Face(a, d, project(x, y, Z), project(x, y, z), right, null, null));
        }
        if (rotation == 0 || rotation == 3) {
            faces.add(new Face(c, d, project(x, y, Z), project(X, y, Z), left, null, null));
        } else {
            faces.add(new Face(a, b, project(X, y, z), project(x, y, z), left, null, null));
        }
    }

    private Point project(double x, double y, double z) {
        float dx = (float) (x - origin.getX()), dz = (float) (z - origin.getZ());
        float u = switch (rotation) { case 1 -> -dz; case 2 -> -dx; case 3 -> dz; default -> dx; };
        float v = switch (rotation) { case 1 -> dx; case 2 -> -dz; case 3 -> -dx; default -> dz; };
        float h = (float) (y - origin.getY());
        var point = new Point((u - v) * .866f, (u + v) * .42f - h * .86f, u + v + h);
        minX = Math.min(minX, point.x); maxX = Math.max(maxX, point.x);
        minY = Math.min(minY, point.y); maxY = Math.max(maxY, point.y);
        return point;
    }

    boolean render(GuiGraphics graphics, int x, int y, int width, int height) {
        OrbitUi.well(graphics, x, y, width, height, true);
        if (faces.isEmpty()) return true;
        if (raster == null && texture == null || rasterWidth != width || rasterHeight != height) {
            close();
            rasterWidth = width;
            rasterHeight = height;
            raster = CompletableFuture.supplyAsync(() -> rasterize(width, height), Util.backgroundExecutor());
        }
        if (texture == null) {
            var pixels = raster.getNow(null);
            if (pixels == null) return false;
            var canvas = new NativeImage(width, height, false);
            for (int py = 0; py < height; py++) for (int px = 0; px < width; px++) {
                int color = pixels[py * width + px];
                canvas.setPixelRGBA(px, py, (color & 0xFF00FF00) | (color & 255) << 16 | (color >>> 16 & 255));
            }
            texture = Minecraft.getInstance().getTextureManager().register("orbital_preview", new DynamicTexture(canvas));
            uploadedAt = Util.getMillis();
            raster = null;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.blit(texture, x, y, 0f, 0f, width, height, width, height);
        RenderSystem.disableBlend();
        return true;
    }

    long uploadedAt() { return uploadedAt; }

    @Override public void close() {
        if (texture != null) Minecraft.getInstance().getTextureManager().release(texture);
        texture = null;
        if (raster != null) raster.cancel(false);
        raster = null;
        uploadedAt = 0;
    }

    private Projection projection(int width, int height) {
        float scale = Math.min((width - 16) / Math.max(1, maxX - minX), (height - 12) / Math.max(1, maxY - minY));
        return new Projection(scale, (width - (maxX + minX) * scale) / 2, (height - (maxY + minY) * scale) / 2);
    }

    private int[] rasterize(int width, int height) {
        var view = projection(width, height);
        float scale = view.scale, centerX = view.x, centerY = view.y;
        int[] detailed = rasterizeFaces(width * 2, height * 2, scale * 2, centerX * 2, centerY * 2);
        int[] pixels = new int[width * height];
        int stride = width * 2;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int offset = y * 2 * stride + x * 2;
            pixels[y * width + x] = resolvePixel(detailed[offset], detailed[offset + 1],
                    detailed[offset + stride], detailed[offset + stride + 1]);
        }
        for (var marker : sockets.values()) drawMarker(pixels, width, height, marker,
                centerX, centerY, scale, 0xFF385D56, 0xFFA3EDB7);
        for (var marker : site) {
            int x = Math.round(centerX + marker.position.x * scale), y = Math.round(centerY + marker.position.y * scale);
            if (x >= 1 && x < width - 1 && y >= 1 && y < height - 1) {
                pixels[y * width + x] = pixels[y * width + x + 1] = pixels[(y + 1) * width + x] = marker.color;
            }
        }
        drawMarker(pixels, width, height, dockMarker, centerX, centerY, scale, 0xFF644617, 0xFFFFD95B);
        drawMarker(pixels, width, height, galleryMarker, centerX, centerY, scale, 0xFF542A80, 0xFFE2B6FF);
        drawMarker(pixels, width, height, problemMarker, centerX, centerY, scale, 0xFF70151D, 0xFFFF6965);
        return pixels;
    }

    private static int resolvePixel(int a, int b, int c, int d) {
        int count = (a >>> 24) / 255 + (b >>> 24) / 255 + (c >>> 24) / 255 + (d >>> 24) / 255;
        if (count == 0) return 0;
        int red = ((a >>> 16 & 255) + (b >>> 16 & 255) + (c >>> 16 & 255) + (d >>> 16 & 255)) / count;
        int green = ((a >>> 8 & 255) + (b >>> 8 & 255) + (c >>> 8 & 255) + (d >>> 8 & 255)) / count;
        int blue = ((a & 255) + (b & 255) + (c & 255) + (d & 255)) / count;
        return (count * 255 / 4) << 24 | red << 16 | green << 8 | blue;
    }

    private int[] rasterizeFaces(int width, int height, float scale, float centerX, float centerY) {
        int[] pixels = new int[width * height];
        for (var face : faces) {
            Point[] points = {face.a, face.b, face.c, face.d};
            float[] xs = new float[4];
            float[] ys = new float[4];
            float low = height, high = 0;
            for (int i = 0; i < 4; i++) {
                xs[i] = centerX + points[i].x * scale;
                ys[i] = centerY + points[i].y * scale;
                low = Math.min(low, ys[i]);
                high = Math.max(high, ys[i]);
            }
            if (face.texture != null) {
                texturedTriangle(pixels, width, height, face, xs, ys, 0, 1, 2);
                texturedTriangle(pixels, width, height, face, xs, ys, 0, 2, 3);
                continue;
            }
            for (int row = Math.max(0, (int) low); row < Math.min(height, Math.ceil(high)); row++) {
                float left = width, right = -1;
                for (int i = 0; i < 4; i++) {
                    int j = (i + 1) % 4;
                    float line = row + .5f;
                    if ((ys[i] > line) == (ys[j] > line)) continue;
                    float crossing = xs[i] + (line - ys[i]) * (xs[j] - xs[i]) / (ys[j] - ys[i]);
                    left = Math.min(left, crossing);
                    right = Math.max(right, crossing);
                }
                for (int col = Math.max(0, Math.round(left)); col < Math.min(width, Math.round(right)); col++) {
                    pixels[row * width + col] = 0xFF000000 | face.color;
                }
            }
        }
        return pixels;
    }

    private static void texturedTriangle(int[] pixels, int width, int height, Face face,
                                         float[] x, float[] y, int a, int b, int c) {
        float area = (y[b] - y[c]) * (x[a] - x[c]) + (x[c] - x[b]) * (y[a] - y[c]);
        if (Math.abs(area) < .0001f) return;
        int xMin = Math.max(0, (int) Math.floor(Math.min(x[a], Math.min(x[b], x[c]))));
        int yMin = Math.max(0, (int) Math.floor(Math.min(y[a], Math.min(y[b], y[c]))));
        int xMax = Math.min(width, (int) Math.ceil(Math.max(x[a], Math.max(x[b], x[c]))));
        int yMax = Math.min(height, (int) Math.ceil(Math.max(y[a], Math.max(y[b], y[c]))));
        for (int row = yMin; row < yMax; row++) for (int col = xMin; col < xMax; col++) {
            float dx = col + .5f - x[c], dy = row + .5f - y[c];
            float wa = ((y[b] - y[c]) * dx + (x[c] - x[b]) * dy) / area;
            float wb = ((y[c] - y[a]) * dx + (x[a] - x[c]) * dy) / area;
            float wc = 1 - wa - wb;
            if (wa < 0 || wb < 0 || wc < 0) continue;
            float u = wa * face.uv[2 * a] + wb * face.uv[2 * b] + wc * face.uv[2 * c];
            float v = wa * face.uv[2 * a + 1] + wb * face.uv[2 * b + 1] + wc * face.uv[2 * c + 1];
            int pixel = face.texture.at(u, v);
            int alpha = pixel >>> 24;
            if (alpha == 0) continue;
            int index = row * width + col;
            int background = pixels[index] == 0 ? 0x0B171F : pixels[index];
            pixels[index] = 0xFF000000
                    | blendChannel(pixel, face.color, background, alpha, 16) << 16
                    | blendChannel(pixel, face.color, background, alpha, 8) << 8
                    | blendChannel(pixel, face.color, background, alpha, 0);
        }
    }

    private static int blendChannel(int texel, int tint, int background, int alpha, int shift) {
        int lit = (texel >>> shift & 255) * (tint >>> shift & 255) / 255;
        return (lit * alpha + (background >>> shift & 255) * (255 - alpha)) / 255;
    }

    private static void drawMarker(int[] pixels, int width, int height, Point marker,
                                   float centerX, float centerY, float scale, int border, int color) {
        if (marker == null) return;
        int markX = Math.round(centerX + marker.x * scale);
        int markY = Math.round(centerY + marker.y * scale);
        for (int dy = -3; dy <= 3; dy++) for (int dx = -3; dx <= 3; dx++) {
            int col = markX + dx, row = markY + dy;
            int distance = Math.abs(dx) + Math.abs(dy);
            if (distance <= 3 && col >= 0 && col < width && row >= 0 && row < height) {
                pixels[row * width + col] = distance == 3 ? border : color;
            }
        }
    }
}



