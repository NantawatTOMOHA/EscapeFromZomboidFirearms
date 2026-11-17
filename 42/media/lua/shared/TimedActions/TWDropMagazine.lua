-- Quick reload, as Tarkov's double-tap: the inserted magazine is pulled out and dropped at the
-- character's feet instead of going back into the inventory (user 2026-10-16: "tactical reload
-- with the magazine dropped"). Derived from the vanilla eject so every hook on it (Gunworks
-- magazine profiles, ammo lists, visual attachment; TW_ReloadVisual) still runs; the anim
-- variable TWMagDrop picks the class's drop node (Tarkov <weapon>_mag_out_all), which falls back
-- to the ordinary magazine-out node where a class has none. The eject takes DROP_SCALE of the
-- vanilla time (Tarkov: mag_out_all 1.0 s against mag_out 1.43 s). The next magazine is queued by
-- the caller (TW_ReloadRadial.quickReload).
require 'TimedActions/ISEjectMagazine'
local Anim=require('TW/AnimOptOut') -- W8 animopt: the two "vanilla animations" options

TWDropMagazine = ISEjectMagazine:derive('TWDropMagazine')

local DROP_SCALE = 0.7

function TWDropMagazine:start()
    -- W8 animopt (the user's item 9): with this view's option on the vanilla magazine-out clip plays, no drop node.
    if Anim.poses(self.character) then self:setAnimVariable('TWMagDrop', true) end
    return ISEjectMagazine.start(self)
end

-- The magazines of the weapon's type already carried: the one the eject adds is the new one.
local function known(inv, magType)
    local ids = {}
    local items = magType and inv:getAllTypeRecurse(magType)
    if items then
        for i = 0, items:size() - 1 do ids[items:get(i):getID()] = true end
    end
    return ids
end

function TWDropMagazine:unloadAmmo()
    local inv = self.character:getInventory()
    local clip = self.gun and self.gun:getWeaponPart('Clip')
    local magType = clip and clip:getFullType() or self.gun:getMagazineType()
    local before = known(inv, magType)
    ISEjectMagazine.unloadAmmo(self)
    local items = magType and inv:getItemsFromFullType(magType)
    local dropped
    if items then
        for i = 0, items:size() - 1 do
            local item = items:get(i)
            if not before[item:getID()] then dropped = item end
        end
    end
    local sq = self.character:getCurrentSquare()
    if not dropped or not sq then return end
    -- at the feet, a little to the support side and ahead
    local x = self.character:getX() - math.floor(self.character:getX()) + ZombRandFloat(-0.15, 0.15)
    local y = self.character:getY() - math.floor(self.character:getY()) + ZombRandFloat(-0.15, 0.15)
    local worldItem = sq:AddWorldInventoryItem(dropped, math.max(0.05, math.min(0.95, x)), math.max(0.05, math.min(0.95, y)), 0, false)
    if worldItem and worldItem:getWorldItem() then
        worldItem:getWorldItem():setIgnoreRemoveSandbox(true)
        worldItem:getWorldItem():transmitCompleteItemToClients()
    end
    inv:Remove(dropped)
    sendRemoveItemFromContainer(inv, dropped)
end

-- Faster than the ordinary eject: the node clip runs at the reload speed over DROP_SCALE.
function TWDropMagazine:initVars()
    ISEjectMagazine.initVars(self)
    local speed = self.character:getVariableFloat('ReloadSpeed', 1)
    self.character:setVariable('ReloadSpeed', speed / DROP_SCALE)
end

function TWDropMagazine:serverStart()
    self:initVars()
    emulateAnimEventOnce(self.netAction, ISReloadWeaponAction.getReloadTime(self.character, math.floor(1200 * DROP_SCALE)), 'unloadFinished', nil)
end

function TWDropMagazine:stop()
    self.character:clearVariable('TWMagDrop')
    return ISEjectMagazine.stop(self)
end

function TWDropMagazine:perform()
    self.character:clearVariable('TWMagDrop')
    return ISEjectMagazine.perform(self)
end

function TWDropMagazine:new(character, gun)
    local o = ISEjectMagazine.new(self, character, gun)
    o.twDrop = true
    return o
end
