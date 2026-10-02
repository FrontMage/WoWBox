#include "wowbox/application_filter.hpp"
#include "wowbox/present_route_selector.hpp"

#include <cassert>
#include <cstdint>

int main() {
    assert(WowBox::isAllowedApplicationName(
        WowBox::normalizeApplicationName("Wow.exe")));
    assert(WowBox::isAllowedApplicationName(
        WowBox::normalizeApplicationName("C:\\Games\\_classic_\\WOWCLASSIC.EXE")));
    assert(WowBox::isAllowedApplicationName(
        WowBox::normalizeApplicationName("/mnt/d/TestD3D.exe")));

    assert(!WowBox::isAllowedApplicationName(
        WowBox::normalizeApplicationName("Battle.net.exe")));
    assert(!WowBox::isAllowedApplicationName(
        WowBox::normalizeApplicationName("Agent.exe")));
    assert(!WowBox::isAllowedApplicationName(
        WowBox::normalizeApplicationName("WowClassicHelper.exe")));
    assert(!WowBox::isAllowedApplicationName(
        WowBox::normalizeApplicationName(nullptr)));

    assert(WowBox::normalizeEngineName("DXVK") == "dxvk");
    assert(WowBox::normalizeEngineName("vkd3d-proton") == "vkd3d-proton");
    assert(WowBox::isAllowedEngineName("dxvk"));
    assert(WowBox::isAllowedEngineName("vkd3d-proton"));
    assert(!WowBox::isAllowedEngineName(""));
    assert(!WowBox::isAllowedEngineName("wine"));

    WowBox::PresentRouteSelector selector;
    constexpr uintptr_t first = 0x100;
    constexpr uintptr_t second = 0x200;
    assert(!selector.observeSuccess(first, 100).selected);
    assert(!selector.observeSuccess(first, 200).selected);
    auto selected = selector.observeSuccess(first, 300);
    assert(selected.selected && selected.newlySelected);
    assert(!selector.observeSuccess(second, 400).selected);

    selector.remove(first);
    assert(!selector.observeSuccess(second, 500).selected);
    assert(selector.observeSuccess(second, 600).selected);

    selector.reset();
    assert(!selector.observeSuccess(first, 1000).selected);
    assert(!selector.observeSuccess(first, 2501).selected);
    assert(!selector.observeSuccess(first, 2600).selected);
    assert(selector.observeSuccess(first, 2700).selected);

    selector.reset();
    assert(!selector.observeSuccess(first, 100).selected);
    assert(!selector.observeSuccess(first, 200).selected);
    assert(selector.observeSuccess(first, 300).selected);
    assert(!selector.observeSuccess(second, 1800).selected);
    assert(!selector.observeSuccess(second, 2500).selected);
    auto failover = selector.observeSuccess(
        second, 300 + WowBox::PresentRouteSelector::OWNER_STALE_MS + 1);
    assert(failover.selected && failover.newlySelected);
    return 0;
}
