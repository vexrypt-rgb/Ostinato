/*
 * Ostinato: no-op GPU stand-ins for runMode=headless.
 * Nothing in this file calls SDL or GL. The objects exist so 26.3 construction and the client tick
 * can ask for a texture, a buffer, or a command encoder and get an empty answer.
 */
package baritone.launch.headless;

import baritone.api.headless.HeadlessPolicy;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.joml.Vector4fc;
import org.lwjgl.PointerBuffer;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** The one stub device a headless process installs in place of {@code GpuBackend.createDevice}. */
public final class HeadlessDevices {

    private static final Device DEVICE = new Device();

    private HeadlessDevices() {}

    public static GpuDevice device() {
        return DEVICE;
    }

    /** Same value as {@link HeadlessPolicy#FAKE_WINDOW_HANDLE}. Not an SDL id. */
    public static long windowHandle() {
        return HeadlessPolicy.FAKE_WINDOW_HANDLE;
    }

    private static ByteBuffer bytes(long size) {
        long n = size <= 0 ? 1L : Math.min(size, 8L * 1024L * 1024L);
        return ByteBuffer.allocateDirect((int) n);
    }

    private static final class Device implements GpuDevice {
        private final DeviceInfo info;

        Device() {
            // Ostinato: large enough that atlas code accepts the limit. No native allocation follows.
            DeviceLimits limits = new DeviceLimits(1, 256, 16384, 1L << 34, 1024, 8, 1024);
            DeviceFeatures features = new DeviceFeatures(false, false, false, false, false, false, false, false);
            HintsAndWorkarounds hints = new HintsAndWorkarounds(false, false, false, false);
            this.info = new DeviceInfo(
                    "Ostinato headless",
                    "none",
                    "stub",
                    false,
                    "headless",
                    1.0f,
                    limits,
                    features,
                    Set.of(),
                    hints,
                    DeviceType.CPU);
        }

        @Override
        public GpuSurface createSurface(long window, BooleanSupplier vsync) {
            return Surface.INSTANCE;
        }

        @Override
        public CommandEncoder createCommandEncoder() {
            return Encoder.INSTANCE;
        }

        @Override
        public GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int anisotropy, OptionalDouble maxLod) {
            return new Sampler(u, v, min, mag, anisotropy, maxLod);
        }

        @Override
        public GpuTexture createTexture(Supplier<String> label, int usage, GpuFormat format, int width, int height, int depth, int mips) {
            return new Texture(label == null ? "" : label.get(), usage, format, width, height, depth, mips);
        }

        @Override
        public GpuTexture createTexture(String label, int usage, GpuFormat format, int width, int height, int depth, int mips) {
            return new Texture(label, usage, format, width, height, depth, mips);
        }

        @Override
        public GpuTextureView createTextureView(GpuTexture texture) {
            return new View(texture, 0, texture == null ? 1 : Math.max(1, texture.getMipLevels()));
        }

        @Override
        public GpuTextureView createTextureView(GpuTexture texture, int base, int levels) {
            return new View(texture, base, levels);
        }

        @Override
        public GpuBuffer createBuffer(Supplier<String> label, int usage, long size) {
            return new Buffer(usage, size);
        }

        @Override
        public GpuBuffer createBuffer(Supplier<String> label, int usage, ByteBuffer data) {
            long size = data == null ? 0L : data.remaining();
            return new Buffer(usage, size);
        }

        @Override
        public List<String> getLastDebugMessages() {
            return List.of();
        }

        @Override
        public boolean isDebuggingEnabled() {
            return false;
        }

        @Override
        public CompletableFuture<CompiledRenderPipeline.Pending> compilePipeline(RenderPipeline pipeline, ShaderSource source, Executor executor) {
            return CompletableFuture.completedFuture(Pipeline.PENDING);
        }

        @Override
        public void close() {
        }

        @Override
        public GpuQueryPool createTimestampQueryPool(int size) {
            return new Queries(size);
        }

        @Override
        public DeviceInfo getDeviceInfo() {
            return info;
        }
    }

    /** Never acquired, so {@code renderFrame} does not blit or present. */
    private static final class Surface implements GpuSurface {
        static final Surface INSTANCE = new Surface();

        @Override
        public void configure(GpuSurface.Configuration configuration) {
        }

        @Override
        public Optional<GpuSurface.Configuration> currentConfiguration() {
            return Optional.empty();
        }

        @Override
        public Collection<GpuSurface.PresentMode> supportedPresentModes() {
            // Ostinato: renderFrame picks a vsync mode from this set. An empty or partial set throws.
            return List.of(
                    GpuSurface.PresentMode.IMMEDIATE,
                    GpuSurface.PresentMode.MAILBOX,
                    GpuSurface.PresentMode.FIFO,
                    GpuSurface.PresentMode.FIFO_RELAXED);
        }

        @Override
        public boolean isSuboptimal() {
            return false;
        }

        @Override
        public boolean isAcquired() {
            return false;
        }

        @Override
        public void acquireNextTexture() {
        }

        @Override
        public void blitFromTexture(CommandEncoder encoder, GpuTextureView view) {
        }

        @Override
        public void present() {
        }

        @Override
        public void close() {
        }
    }

    private static final class Encoder implements CommandEncoder {
        static final Encoder INSTANCE = new Encoder();

        @Override
        public void submit() {
        }

        @Override
        public TransientMemory transientMemory() {
            return Memory.INSTANCE;
        }

        @Override
        public RenderPass createRenderPass(RenderPassDescriptor descriptor) {
            return Pass.INSTANCE;
        }

        @Override
        public void clearColorTexture(GpuTexture texture, Vector4fc color) {
        }

        @Override
        public void clearColorAndDepthTextures(GpuTexture color, Vector4fc clear, GpuTexture depth, double depthValue) {
        }

        @Override
        public void clearColorAndDepthTextures(GpuTexture color, Vector4fc clear, GpuTexture depth, double depthValue, int x, int y, int z, int width, int height) {
        }

        @Override
        public void clearDepthTexture(GpuTexture texture, double depth) {
        }

        @Override
        public void writeToBuffer(GpuBufferSlice slice, ByteBuffer data) {
        }

        @Override
        public void copyToBuffer(GpuBufferSlice src, GpuBufferSlice dst) {
        }

        @Override
        public void writeToTexture(GpuTexture texture, NativeImage image) {
        }

        @Override
        public void writeToTexture(GpuTexture texture, NativeImage image, int mip, int x, int y, int depth) {
        }

        @Override
        public void writeToTexture(GpuTexture texture, ByteBuffer data, int mip, int x, int y, int width, int height, int depth) {
        }

        @Override
        public void copyBufferToTexture(GpuBufferSlice slice, int stride, int rowLength, int imageHeight, int unused, GpuTexture texture, int mip, int x, int y, int z, int width, int height) {
        }

        @Override
        public void copyTextureToBuffer(GpuTexture texture, GpuBuffer buffer, long offset, Runnable done, int mip) {
            if (done != null) {
                done.run();
            }
        }

        @Override
        public void copyTextureToBuffer(GpuTexture texture, GpuBuffer buffer, long offset, Runnable done, int mip, int x, int y, int z, int depth) {
            if (done != null) {
                done.run();
            }
        }

        @Override
        public void copyTextureToTexture(GpuTexture src, GpuTexture dst, int mip, int srcX, int srcY, int dstX, int dstY, int width, int height) {
        }

        @Override
        public GpuFence createFence() {
            return Fence.INSTANCE;
        }

        @Override
        public void writeTimestamp(GpuQueryPool pool, int index) {
        }
    }

    private static final class Memory implements TransientMemory {
        static final Memory INSTANCE = new Memory();
        private final GpuBuffer scratch = new Buffer(GpuBuffer.USAGE_COPY_DST, 4);

        @Override
        public ByteBuffer allocateCpu(long size, long alignment, long unusedA, long unusedB) {
            return bytes(size);
        }

        @Override
        public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage, long unusedA, long unusedB) {
            return scratch.map(0L, size, false, true);
        }

        @Override
        public GpuBufferSlice allocateGpu(long size, long alignment, int usage, long unusedA, long unusedB) {
            return new GpuBufferSlice(scratch, 0L, size);
        }

        @Override
        public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage, long unusedA, long unusedB) {
            return scratch.map(0L, size, true, true);
        }

        @Override
        public GpuBufferSlice uploadStaging(List<ByteBuffer> buffers, long alignment, int usage, long unusedA, long unusedB) {
            return new GpuBufferSlice(scratch, 0L, 4);
        }

        @Override
        public GpuBufferSlice uploadGpu(List<ByteBuffer> buffers, long alignment, int usage, long unusedA, long unusedB) {
            return new GpuBufferSlice(scratch, 0L, 4);
        }

        @Override
        public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> buffers, long alignment, int usage) {
            return List.of(new GpuBufferSlice(scratch, 0L, 4));
        }

        @Override
        public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> buffers, long alignment, int usage) {
            return List.of(new GpuBufferSlice(scratch, 0L, 4));
        }
    }

    private static final class Pass implements RenderPass {
        static final Pass INSTANCE = new Pass();

        @Override
        public void pushDebugGroup(Supplier<String> label) {
        }

        @Override
        public void popDebugGroup() {
        }

        @Override
        public void writeTimestamp(GpuQueryPool pool, int index) {
        }

        @Override
        public void setPipeline(CompiledRenderPipeline pipeline) {
        }

        @Override
        public void setUniform(String name, GpuTextureView view, GpuSampler sampler) {
        }

        @Override
        public void setUniform(String name, GpuBuffer buffer) {
        }

        @Override
        public void setUniform(String name, GpuBufferSlice slice) {
        }

        @Override
        public void pushConstants(ByteBuffer data) {
        }

        @Override
        public void enableScissor(int x, int y, int width, int height) {
        }

        @Override
        public void disableScissor() {
        }

        @Override
        public void setVertexBuffer(int slot, GpuBufferSlice slice) {
        }

        @Override
        public void setIndexBuffer(GpuBuffer buffer, IndexType type) {
        }

        @Override
        public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        }

        @Override
        public void multiDrawIndexed(IntBuffer counts, int indexCount, int instanceCount, int first) {
        }

        @Override
        public void multiDrawIndexed(PointerBuffer offsets, IntBuffer counts, IntBuffer instances, int drawCount) {
        }

        @Override
        public void drawIndexedIndirect(GpuBufferSlice slice, int drawCount) {
        }

        @Override
        public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, GpuBuffer buffer, IndexType type, Collection<String> labels, T user) {
        }

        @Override
        public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        }

        @Override
        public void multiDraw(IntBuffer counts, int vertexCount, int instanceCount, int first) {
        }

        @Override
        public void multiDraw(IntBuffer offsets, IntBuffer counts, int drawCount) {
        }

        @Override
        public void drawIndirect(GpuBufferSlice slice, int drawCount) {
        }

        @Override
        public void close() {
        }
    }

    private static final class Fence implements GpuFence {
        static final Fence INSTANCE = new Fence();

        @Override
        public void close() {
        }

        @Override
        public boolean awaitCompletion(long timeout) {
            return true;
        }
    }

    private static final class Queries implements GpuQueryPool {
        private final int size;

        Queries(int size) {
            this.size = size;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public OptionalLong getValue(int index) {
            return OptionalLong.empty();
        }

        @Override
        public OptionalLong[] getValues(int start, int count) {
            return new OptionalLong[Math.max(0, count)];
        }

        @Override
        public void close() {
        }
    }

    private static final class Pipeline implements CompiledRenderPipeline {
        static final Pipeline INSTANCE = new Pipeline();
        static final CompiledRenderPipeline.Pending PENDING = () -> INSTANCE;

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void close() {
        }
    }

    private static final class Texture implements GpuTexture {
        private final String label;
        private final int usage;
        private final GpuFormat format;
        private final int width;
        private final int height;
        private final int depth;
        private final int mips;
        private boolean closed;

        Texture(String label, int usage, GpuFormat format, int width, int height, int depth, int mips) {
            this.label = label == null ? "" : label;
            this.usage = usage;
            this.format = format;
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            this.depth = Math.max(1, depth);
            this.mips = Math.max(1, mips);
        }

        @Override
        public int getWidth(int mip) {
            return Math.max(1, width >> Math.max(0, mip));
        }

        @Override
        public int getHeight(int mip) {
            return Math.max(1, height >> Math.max(0, mip));
        }

        @Override
        public int getDepthOrLayers() {
            return depth;
        }

        @Override
        public int getMipLevels() {
            return mips;
        }

        @Override
        public GpuFormat getFormat() {
            return format;
        }

        @Override
        public int usage() {
            return usage;
        }

        @Override
        public String getLabel() {
            return label;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class View implements GpuTextureView {
        private final GpuTexture texture;
        private final int base;
        private final int levels;
        private boolean closed;

        View(GpuTexture texture, int base, int levels) {
            this.texture = texture;
            this.base = base;
            this.levels = Math.max(1, levels);
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public GpuTexture texture() {
            return texture;
        }

        @Override
        public int baseMipLevel() {
            return base;
        }

        @Override
        public int mipLevels() {
            return levels;
        }

        @Override
        public int getWidth(int mip) {
            return texture == null ? 1 : texture.getWidth(base + mip);
        }

        @Override
        public int getHeight(int mip) {
            return texture == null ? 1 : texture.getHeight(base + mip);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class Sampler implements GpuSampler {
        private final AddressMode u;
        private final AddressMode v;
        private final FilterMode min;
        private final FilterMode mag;
        private final int anisotropy;
        private final OptionalDouble maxLod;

        Sampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int anisotropy, OptionalDouble maxLod) {
            this.u = u;
            this.v = v;
            this.min = min;
            this.mag = mag;
            this.anisotropy = anisotropy;
            this.maxLod = maxLod == null ? OptionalDouble.empty() : maxLod;
        }

        @Override
        public AddressMode getAddressModeU() {
            return u;
        }

        @Override
        public AddressMode getAddressModeV() {
            return v;
        }

        @Override
        public FilterMode getMinFilter() {
            return min;
        }

        @Override
        public FilterMode getMagFilter() {
            return mag;
        }

        @Override
        public int getMaxAnisotropy() {
            return anisotropy;
        }

        @Override
        public OptionalDouble getMaxLod() {
            return maxLod;
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void close() {
        }
    }

    private static final class Buffer implements GpuBuffer {
        private final int usage;
        private final long size;
        private boolean closed;

        Buffer(int usage, long size) {
            this.usage = usage;
            this.size = Math.max(0L, size);
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public int usage() {
            return usage;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
            GpuBufferSlice slice = new GpuBufferSlice(this, offset, length);
            return new GpuBufferSlice.MappedView(slice, bytes(length), () -> {
            });
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
