-- Loose engine copies of a weapon part (wave W8, 25.10.2026, the user's item 5: "some weapon parts have several
-- duplicates, of which only one works"). A part that a weapon carries several times needs one item type per native copy
-- lane: `P_<tpl>` is the part, `P_<tpl>_1`, `_2` … are the lanes the assembly installs the second, third … copy as
-- (TW/Runtime.typeFor). The generator marks every lane `hidden` so item lists no longer show them as duplicates of the
-- part, and TW/Runtime now installs a loose lane as its template. An old save or an admin spawn can still hold a lane
-- item in a container, where a hidden type would be an invisible item; this file turns such an item back into the
-- ordinary part when the player loads in, keeping its ID, condition, favourite flag, name and mod data.
local D=require('TW/Data')
local A={converted=0}
TWPartAliases=A

-- The ordinary part type of a copy-lane item, or nil for every other item.
function A.baseType(item)
    local meta=item and D.types[item:getFullType()]
    if not meta or meta.copy==0 then return nil end
    local entry=D.items[meta.tpl]
    return entry and entry.pz or nil
end

-- Replace a loose lane item in its container by the ordinary part. Returns the replacement (nil for other items).
function A.fix(item)
    local full=A.baseType(item)
    if not full then return nil end
    local container=item:getContainer()
    if not container then return nil end
    local replacement=instanceItem(full)
    if not replacement then return nil end
    replacement:copyModData(item:getModData())
    replacement:setID(item:getID())
    replacement:setCondition(item:getCondition())
    replacement:setFavorite(item:isFavorite())
    if item:isCustomName() then replacement:setName(item:getName());replacement:setCustomName(true) end
    if replacement.copyTimesRepairedFrom then replacement:copyTimesRepairedFrom(item) end
    if replacement.copyConditionStatesFrom then replacement:copyConditionStatesFrom(item) end
    if item.getCurrentUsesFloat and replacement.setCurrentUsesFloat then
        replacement:setCurrentUsesFloat(item:getCurrentUsesFloat())
    end
    container:Remove(item)
    local added=container:AddItem(replacement) or replacement
    if sendAddItemToContainer then sendAddItemToContainer(container,added) end
    A.converted=A.converted+1
    return added
end

-- Player inventory and the bags inside it (the mod's reach: TW/Runtime.REACH_DEPTH).
local DEPTH=3
local function scan(container,depth)
    local items=container:getItems()
    for i=items:size()-1,0,-1 do
        local item=items:get(i)
        local replaced=A.fix(item)
        if not replaced and depth<DEPTH and instanceof(item,'InventoryContainer') then
            scan(item:getInventory(),depth+1)
        end
    end
end

function A.normalize(player)
    if not player or isClient() then return 0 end
    local before=A.converted
    local ok,err=pcall(scan,player:getInventory(),0)
    if not ok then
        print('[TW] Part alias migration skipped: '..tostring(err))
        return 0
    end
    return A.converted-before
end

local function allPlayers()
    for i=0,getNumActivePlayers()-1 do A.normalize(getSpecificPlayer(i)) end
end

Events.OnGameStart.Add(function()
    allPlayers()
    if A.converted>0 then print('[TW] Part aliases restored to ordinary parts: '..A.converted) end
end)
Events.OnCreatePlayer.Add(function(_,player) A.normalize(player or getSpecificPlayer(0)) end)

return A
