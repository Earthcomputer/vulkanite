package me.cortex.vulkanite.lib.other.sync;

import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.opengl.EXTSemaphore.glGenSemaphoresEXT;
import static org.lwjgl.opengl.EXTSemaphore.glIsSemaphoreEXT;
import static org.lwjgl.opengl.EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT;
import static org.lwjgl.opengl.EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT;
import static org.lwjgl.opengl.GL11C.glGetError;
import static org.lwjgl.opengl.KHRRobustness.GL_NO_ERROR;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;

//TODO: the sync manager (probably rename) manages all sync operations
// should expand it to also add a fence watcher, that is can register a callback on a fence to wait for completion
// use this to add a cleanup system for e.g. single use command buffers is a good example, the fences themselves
// semaphores, scratch/temp buffers etc
public class SyncManager {
    private final VkDevice device;
    public SyncManager(VkDevice device) {
        this.device = device;
    }

    public VFence createFence() {
        try (var stack = stackPush()) {
            LongBuffer res = stack.callocLong(1);
            _CHECK_(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, res));
            return new VFence(device, res.get(0));
        }
    }

    public VGSemaphore createSharedBinarySemaphore() {
        try (var stack = stackPush()) {
            LongBuffer res = stack.callocLong(1);
            _CHECK_(vkCreateSemaphore(device,
                    VkSemaphoreCreateInfo.calloc(stack)
                            .sType$Default()
                            .pNext(VkExportSemaphoreCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .handleTypes(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT)),
                    null, res));
            long semaphore = res.get(0);

            PointerBuffer pb = stack.callocPointer(1);
            _CHECK_(vkGetSemaphoreWin32HandleKHR(device,
                    VkSemaphoreGetWin32HandleInfoKHR.calloc(stack)
                        .sType$Default()
                        .semaphore(semaphore)
                        .handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT),
                    pb));

            if (pb.get(0)== 0) {
                throw new IllegalStateException();
            }
            int glSemaphore = glGenSemaphoresEXT();
            glImportSemaphoreWin32HandleEXT(glSemaphore, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, pb.get(0));
            if (!glIsSemaphoreEXT(glSemaphore))
                throw new IllegalStateException();
            if (glGetError() != GL_NO_ERROR)
                throw new IllegalStateException();

            return new VGSemaphore(device, semaphore, glSemaphore);
        }
    }

    public VSemaphore createBinarySemaphore() {
        try (var stack = stackPush()) {
            LongBuffer res = stack.callocLong(1);
            _CHECK_(vkCreateSemaphore(device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, res));
            return new VSemaphore(device, res.get(0));
        }
    }

    //TODO: MAKE THIS LOCKFREE

    private final Map<VFence, List<Runnable>> callbacks = new HashMap<>();
    public void addCallback(VFence fence, Runnable callback) {
        //The "issue" with this is that since its multithreaded there is a very very small case that (even with lock free) concurrent hashmap
        // the only (simple) way to guarentee this is never the case without locks is to ensure that add callback is called before
        // a fence can ever be marked as signaled
        synchronized (callbacks) {
            callbacks.computeIfAbsent(fence, a->new LinkedList<>()).add(callback);
        }
    }

    //TODO: optimize this
    public void checkFences() {
        synchronized (callbacks) {
            List<VFence> toRemove = new LinkedList<>();
            for (var cb : callbacks.entrySet()) {
                int status = vkGetFenceStatus(device, cb.getKey().address());
                if (status == VK_SUCCESS) {
                    cb.getValue().forEach(Runnable::run);
                    toRemove.add(cb.getKey());
                } else if (status == VK_NOT_READY) {
                    continue;
                }
                _CHECK_(status);
            }
            toRemove.forEach(callbacks::remove);
        }
    }
}
