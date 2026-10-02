#include "wowbox/dispatch_registry.hpp"

#include <cassert>
#include <cstdint>

namespace {
    template<typename T>
    T handle(uintptr_t value) {
        return reinterpret_cast<T>(value);
    }
}

int main() {
    const VkInstance dxvkInstance = handle<VkInstance>(0x1000);
    const VkInstance vkd3dInstance = handle<VkInstance>(0x2000);
    const VkPhysicalDevice dxvkPhysical = handle<VkPhysicalDevice>(0x1100);
    const VkPhysicalDevice vkd3dPhysical = handle<VkPhysicalDevice>(0x2100);
    const VkDevice dxvkDevice = handle<VkDevice>(0x1200);
    const VkDevice vkd3dDevice = handle<VkDevice>(0x2200);
    const VkQueue dxvkQueue = handle<VkQueue>(0x1300);
    const VkQueue vkd3dQueue = handle<VkQueue>(0x2300);
    const VkSwapchainKHR dxvkSwapchain = handle<VkSwapchainKHR>(0x1400);
    const VkSwapchainKHR vkd3dSwapchain = handle<VkSwapchainKHR>(0x2400);
    const VkCommandBuffer dxvkCommand = handle<VkCommandBuffer>(0x1500);
    const VkCommandBuffer vkd3dCommand = handle<VkCommandBuffer>(0x2500);

    WowBox::registerInstance(dxvkInstance, {
        .enabled = true,
        .application = "wowclassic.exe",
        .engine = "dxvk"
    });
    WowBox::registerInstance(vkd3dInstance, {
        .enabled = true,
        .application = "wowclassic.exe",
        .engine = "vkd3d-proton"
    });
    const VkPhysicalDevice dxvkPhysicals[] = {dxvkPhysical};
    const VkPhysicalDevice vkd3dPhysicals[] = {vkd3dPhysical};
    WowBox::registerPhysicalDevices(dxvkInstance, 1, dxvkPhysicals);
    WowBox::registerPhysicalDevices(vkd3dInstance, 1, vkd3dPhysicals);

    assert(WowBox::findPhysicalDevice(dxvkPhysical)->engine == "dxvk");
    assert(WowBox::findPhysicalDevice(vkd3dPhysical)->engine == "vkd3d-proton");
    assert(WowBox::findPhysicalDeviceInstance(dxvkPhysical) == dxvkInstance);
    assert(WowBox::findPhysicalDeviceInstance(vkd3dPhysical) == vkd3dInstance);

    WowBox::registerDevice(dxvkDevice, {
        .enabled = true,
        .instance = dxvkInstance,
        .application = "wowclassic.exe",
        .engine = "dxvk"
    });
    WowBox::registerDevice(vkd3dDevice, {
        .enabled = true,
        .instance = vkd3dInstance,
        .application = "wowclassic.exe",
        .engine = "vkd3d-proton"
    });
    WowBox::registerQueue(dxvkQueue, dxvkDevice);
    WowBox::registerQueue(vkd3dQueue, vkd3dDevice);
    WowBox::registerSwapchain(dxvkSwapchain, dxvkDevice);
    WowBox::registerSwapchain(vkd3dSwapchain, vkd3dDevice);
    const VkCommandBuffer dxvkCommands[] = {dxvkCommand};
    const VkCommandBuffer vkd3dCommands[] = {vkd3dCommand};
    WowBox::registerCommandBuffers(dxvkDevice, 1, dxvkCommands);
    WowBox::registerCommandBuffers(vkd3dDevice, 1, vkd3dCommands);

    assert(WowBox::findQueue(dxvkQueue)->engine == "dxvk");
    assert(WowBox::findQueue(vkd3dQueue)->engine == "vkd3d-proton");
    assert(WowBox::findSwapchain(dxvkSwapchain)->instance == dxvkInstance);
    assert(WowBox::findSwapchain(vkd3dSwapchain)->instance == vkd3dInstance);
    assert(WowBox::findCommandBuffer(dxvkCommand)->engine == "dxvk");
    assert(WowBox::findCommandBuffer(vkd3dCommand)->engine == "vkd3d-proton");

    WowBox::removeDevice(dxvkDevice);
    assert(!WowBox::findDevice(dxvkDevice));
    assert(!WowBox::findQueue(dxvkQueue));
    assert(!WowBox::findSwapchain(dxvkSwapchain));
    assert(!WowBox::findCommandBuffer(dxvkCommand));
    assert(WowBox::findQueue(vkd3dQueue)->engine == "vkd3d-proton");
    assert(WowBox::findSwapchain(vkd3dSwapchain)->instance == vkd3dInstance);
    assert(WowBox::findCommandBuffer(vkd3dCommand)->engine == "vkd3d-proton");

    WowBox::removeInstance(dxvkInstance);
    assert(!WowBox::findInstance(dxvkInstance));
    assert(!WowBox::findPhysicalDevice(dxvkPhysical));
    assert(WowBox::findPhysicalDevice(vkd3dPhysical)->engine == "vkd3d-proton");

    WowBox::removeDevice(vkd3dDevice);
    WowBox::removeInstance(vkd3dInstance);
    return 0;
}
