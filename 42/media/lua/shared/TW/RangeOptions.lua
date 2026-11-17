-- W11 range (player items 1 and 8, 28.10.2026): sandbox multipliers of the reach of PZ's own firearm path.
--
-- With the mod's ballistics off (IsometricBallistics off in single player, or a server that does not run the
-- mod's ballistics) the shot is PZ's own, and that path is limited by HandWeapon.getMaxRange(): the target
-- list and aim assist (CombatManager.calcValidTargets, BallisticsController), the native flight and the
-- dedicated server's AntiCheatHitWeapon distance check (max(0, distance-5) <= weapon.getMaxRange()). The
-- mod's own ballistics flies the Tarkov ranges of TW_Ballistics and never reads MaxRange.
--
-- FirearmRange scales the mod's firearms from the PZ class reach (TW/Data.lua D.rangeBase, written by
-- tools/generate_scripts.py; D.weapons[key].base.MaxRangeBase is the same number for the runtime layer);
-- default 2, the reach before this option. AllFirearmsRange scales every other firearm - vanilla and other
-- mods' - from its own item script; default 1, no change. Melee weapons are never touched.
--
-- Both values are applied to the item scripts (so every weapon created afterwards carries the reach) and to
-- every weapon a player carries on both sides (so a continued save's instances get it too) after the world's
-- sandbox options load (TW/WorldSandbox), and again whenever the values change. That makes the number the
-- same on a shooter's client and on the server, which the dedicated server's anticheat requires; a client
-- cannot widen what the server accepts.
local D=require('TW/Data')

local M={applied=nil,next=0,scans=0,changed=0}
local RANGE=assert(D.range,'TW/Data.lua has no range options: run tools/generate_scripts.py')
local BASE=D.rangeBase or {}

-- The last MaxRange seen for an item type before this module touched it: the item scripts, and the instances
-- a continued save stores, are scaled from it, never from the value a previous application left behind.
local ORIGINAL={}
-- Item type -> 'mod' (an EFZ firearm), 'other' (another firearm) or 'container' for the inventory walk.
local KIND={}
-- What this session already rewrote, so the option can go back to its default: a weapon or an item type we
-- never touched at the default AllFirearmsRange 1 keeps whatever the game or another mod gave it.
local TOUCHED=setmetatable({},{__mode='k'})
local SCRIPT_TOUCHED={}

local function clamp(v,lo,hi)
    if v<lo then return lo elseif v>hi then return hi end
    return v
end

-- SandboxVars.<namespace>.<id>; missing, NaN or out of range -> the generated default clamped to min..max.
function M.value(id)
    local row=id=='FirearmRange' and RANGE.mod or id=='AllFirearmsRange' and RANGE.all or nil
    if not row then return nil end
    local vars=SandboxVars and SandboxVars[RANGE.namespace]
    local v=type(vars)=='table' and tonumber(vars[id]) or nil
    if v==nil or v~=v then v=row.default end
    return clamp(v,row.min,row.max)
end

-- The generated default of one range option (for the mod's firearms the W10 reach, for every other one 1).
function M.defaultOf(id)
    local row=id=='FirearmRange' and RANGE.mod or id=='AllFirearmsRange' and RANGE.all or nil
    return row and row.default or nil
end

-- Reach of an EFZ firearm item script: the PZ class reach times FirearmRange, rounded (the class values are
-- whole cells). TW/Core.pzStats adds the assembly's velocity on top of the same value (M.modReach).
function M.scriptReach(classValue,multiplier)
    return math.max(5,math.floor((classValue or 0)*(multiplier or M.value('FirearmRange') or 1)+0.5))
end

-- The same reach for the mod's runtime stat layer: the class reach times the option, then the assembly's
-- velocity as before W11 (TW/Core.pzStats), never accumulated.
function M.modReach(classValue,velocityDelta)
    local m=M.value('FirearmRange') or 1
    local d=clamp(velocityDelta or 0,-40,40)
    return math.max(5,math.floor((classValue or 0)*m*(1+d/200)+0.5))
end

-- Reach of another firearm: exactly its own reach (the item script plus the parts the game added) times the
-- option, so at 1 the value is the one the game already had and at 2 it is exactly twice as far.
function M.otherReach(reach,multiplier)
    return (reach or 0)*(multiplier or M.value('AllFirearmsRange') or 1)
end

-- Class reach of an EFZ firearm item type (nil for every other item).
function M.classOf(full)
    return BASE[full]
end

-- The reach an item type had before this module touched it (script or instance), for the QA case and the
-- report: nil until the item script has been seen once.
function M.originalOf(full)
    return ORIGINAL[full]
end

local function partsRange(weapon)
    local total=0
    local ok,list=pcall(function() return weapon:getAllWeaponParts() end)
    if not ok or not list then return 0 end
    for i=0,list:size()-1 do
        local ok2,v=pcall(function() return list:get(i):getMaxRange() end)
        if ok2 and type(v)=='number' then total=total+v end
    end
    return total
end

-- The item scripts of every firearm: the mod's ones to the class reach, every other one from its own value.
-- New weapons are created from these (InventoryItemFactory copies the script's MaxRange into the weapon).
function M.applyScripts()
    M.scans=M.scans+1
    local manager=ScriptManager and ScriptManager.instance
    local list=manager and manager.getAllItems and manager:getAllItems() or nil
    if not list then return 0 end
    local mod,all=M.value('FirearmRange'),M.value('AllFirearmsRange')
    local changed=0
    for i=0,list:size()-1 do
        local item=list:get(i)
        if item and item.getMaxRange and item:isRanged() then
            local full=item:getFullName()
            local original=ORIGINAL[full]
            if original==nil then original=item:getMaxRange();ORIGINAL[full]=original end
            local class=BASE[full]
            local target
            if class~=nil then
                target=M.scriptReach(class,mod)
            elseif all~=1 or SCRIPT_TOUCHED[full] then
                target=M.otherReach(original,all)
            end
            KIND[full]=class~=nil and 'mod' or 'other'
            if target~=nil and math.abs(item:getMaxRange()-target)>0.0001 then
                item:setMaxRange(target)
                if class==nil then SCRIPT_TOUCHED[full]=true end
                changed=changed+1
            end
        end
    end
    return changed
end

-- One weapon the player carries. The mod's own weapons on a shooter's client stay with the runtime stat
-- layer (the assembly's Tarkov reach); on a server this is the copy the anticheat reads, so it gets the
-- script reach. Every other firearm - vanilla, another mod's, a continued save's instance - gets its own
-- reach times AllFirearmsRange.
function M.applyWeapon(player,weapon)
    if not weapon or not weapon.getFullType or not weapon.getMaxRange then return false end
    local full=weapon:getFullType()
    local class=BASE[full]
    local target
    if class~=nil then
        if player and player.isLocalPlayer and player:isLocalPlayer() then return false end
        target=M.scriptReach(class)
    else
        local original=ORIGINAL[full]
        if original==nil then return false end -- the item script was not seen yet (M.applyScripts first)
        local all=M.value('AllFirearmsRange')
        if all==1 and not TOUCHED[weapon] then return false end
        target=M.otherReach(original+partsRange(weapon),all)
    end
    if math.abs(weapon:getMaxRange()-target)<=0.0001 then
        if class==nil then TOUCHED[weapon]=true end
        return false
    end
    weapon:setMaxRange(target)
    if class==nil then TOUCHED[weapon]=true end
    return true
end

local function itemKind(item)
    local full=item:getFullType()
    local kind=KIND[full]
    if kind==nil then
        if BASE[full]~=nil then kind='mod'
        elseif instanceof(item,'HandWeapon') and item:isRanged() then kind='other'
        elseif instanceof(item,'InventoryContainer') then kind='container'
        else kind=false end
        KIND[full]=kind
    end
    return kind
end

-- Every weapon the player has: the hands and the inventory with its bags (a saved instance keeps the reach
-- it was stored with, so it has to be rewritten here).
function M.applyPlayer(player)
    if not player or not player.getInventory then return 0 end
    local changed=0
    local primary=player.getPrimaryHandItem and player:getPrimaryHandItem() or nil
    local secondary=player.getSecondaryHandItem and player:getSecondaryHandItem() or nil
    if primary and M.applyWeapon(player,primary) then changed=changed+1 end
    if secondary and secondary~=primary and M.applyWeapon(player,secondary) then changed=changed+1 end
    local pending,seen={player:getInventory()},{}
    while #pending>0 do
        local container=table.remove(pending)
        if container and not seen[container] then
            seen[container]=true
            local list=container:getItems()
            for i=0,list:size()-1 do
                local item=list:get(i)
                if item then
                    local kind=itemKind(item)
                    if kind=='mod' or kind=='other' then
                        if M.applyWeapon(player,item) then changed=changed+1 end
                    elseif kind=='container' then
                        pending[#pending+1]=item:getInventory()
                    end
                end
            end
        end
    end
    return changed
end

local function eachPlayer(fn)
    if isServer and isServer() then
        -- A dedicated server has no local player: the players' carried weapons are their server copies,
        -- the ones AntiCheatHitWeapon reads (remote players do not dispatch OnPlayerUpdate there).
        -- Not before OnServerStarted: the world's sandbox pass runs while the server has no network yet
        -- (GameServer.udpEngine is null), getOnlinePlayers throws there and a -debug server pauses on it;
        -- no player is connected before that anyway (W11 coordinator, found by hostauth's MP stands).
        if not M.serverStarted then return end
        local list=getOnlinePlayers and getOnlinePlayers() or nil
        if list then
            for i=0,list:size()-1 do fn(list:get(i)) end
        end
        return
    end
    local player=getPlayer and getPlayer() or nil
    if player then fn(player) end
end

function M.applyPlayers()
    local changed=0
    eachPlayer(function(player) changed=changed+M.applyPlayer(player) end)
    return changed
end

-- A change of the values must reach the mod's own runtime stat layer: its numbers are cached per weapon
-- until the weapon's assembly changes, so the held weapon is refreshed here.
function M.bump()
    M.token=(M.token or 0)+1
    local player=getPlayer and getPlayer() or nil
    if not player or not player.getPrimaryHandItem then return end
    local weapon=player:getPrimaryHandItem()
    if not (weapon and BASE[weapon:getFullType()]) then weapon=player:getSecondaryHandItem() end
    if not (weapon and BASE[weapon:getFullType()]) then return end
    local ok,R=pcall(require,'TW/Runtime')
    if ok and type(R)=='table' and R.refresh then pcall(R.refresh,weapon,player) end
end

-- force: apply the scripts and the held stat layer too (a world start or a changed value); without force
-- only the carried instances are checked, which is cheap enough for the periodic call.
function M.refresh(force)
    local token=string.format('%.4f/%.4f',M.value('FirearmRange') or -1,M.value('AllFirearmsRange') or -1)
    local changed,applied=0,false
    if force or token~=M.applied then
        M.applied=token
        applied=true
        changed=changed+M.applyScripts()
        M.bump()
    end
    changed=changed+M.applyPlayers()
    M.changed=changed
    if applied and M.value('FirearmRange') then
        print(string.format('[TW] range FirearmRange=%.2f AllFirearmsRange=%.2f scripts+instances changed=%d',
            M.value('FirearmRange'),M.value('AllFirearmsRange'),changed))
    end
    return changed,applied
end

-- Once a second: a value changed while the world runs (admin, another mod) takes effect without a restart.
local function tick()
    local now=getTimestampMs and getTimestampMs() or 0
    if now<M.next then return end
    M.next=now+1000
    M.refresh(false)
end

-- The offline data checks load TW/Core, and so this module, in a bare Lua runtime: no game events there, and
-- the per-tick work is registered only when a world really starts (M.start), never at load.
if type(Events)=='table' then
    local function on(name,fn)
        local e=Events[name]
        if type(e)=='table' and e.Add then e.Add(fn) end
    end
    -- Called by the world start (M.start from OnGameStart and TW/WorldSandbox): the periodic refresh and the
    -- per-update checks. Once per session; the first forced refresh already applied the values.
    function M.start()
        if M.started then return end
        M.started=true
        on('OnTick',tick)
        -- The held weapon is checked every update: an instance picked up, crafted or restored by a mod is
        -- applied at once, not on the next periodic pass.
        on('OnPlayerUpdate',function(player)
            if not player then return end
            M.applyWeapon(player,player.getPrimaryHandItem and player:getPrimaryHandItem() or nil)
            local second=player.getSecondaryHandItem and player:getSecondaryHandItem() or nil
            if second then M.applyWeapon(player,second) end
        end)
        -- The item just equipped (player report of 03.11.2026, frame drops while shooting: an equip walked every bag of
        -- every local player, and Gunworks re-equipped around each shot; another player's equip on a client ran it too).
        -- The rest of the inventory is the periodic pass's (tick).
        local function equipped(player,item)
            if item and instanceof(player,'IsoPlayer') and ((isServer and isServer()) or player:isLocalPlayer()) then
                M.applyWeapon(player,item)
            end
        end
        on('OnEquipPrimary',equipped)
        on('OnEquipSecondary',equipped)
    end
    on('OnGameStart',function() M.refresh(true); M.start() end)
    -- A dedicated server never sends OnGameStart: its players are reachable from here on.
    on('OnServerStarted',function() M.serverStarted=true; M.refresh(true); M.start() end)
end

return M
