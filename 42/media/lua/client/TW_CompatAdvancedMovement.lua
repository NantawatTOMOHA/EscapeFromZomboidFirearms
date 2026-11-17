-- Compatibility with Viewpoint Advanced Movement (player item 10, W9, 26.10.2026):
-- Project Zomboid Workshop 3812702301, mod ids ViewpointAdvancedMovement, ViewpointAdvancedMovementServer
-- and ViewpointClassicCamera, version 1.4.18, Java package com.viewpointmovement, read-only at
-- D:\SteamLibrary\steamapps\workshop\content\108600\3812702301\mods.
--
-- What it adds that meets our first-person layer: its own body pose (lean, crouch, jump), real stances our
-- mod does not model (prone with crawl/roll/get-up, climbing onto furniture and roofs, hanging from a ledge,
-- the window vault) and its own camera work (eye travel, roll). Its pose is laid at the entry of
-- viewpoint.Hooks.skeletonPosed; our layers run later, at the AnimationPlayer.UpdateSkinTransforms entry, so
-- our first-person arms/weapon would overdraw a lying or climbing body and the weapon would hang in front of
-- the face. This file only tells our layers which stance owns the arms; detection is by mod id and the
-- add-on's public Lua API, never by mod order.
--
-- Rules (coordinator-approved, W9-advmove):
--   * climb, hang, window vault, getting down/up, rolling: aiming is impossible there - the arms and weapon
--     are Advanced Movement's, our first-person pose fades out (TWAdvMoveYield read by FpPose.bodyState).
--   * lying still (front or back): aiming works in Advanced Movement, so our pose keeps it - the sight is put
--     on the eye Viewpoint itself has at the head (CameraBridge.proneEye), and our ballistics fires from the
--     drawn weapon. Only while NOT aiming does the layer yield to the prone clip.
--   * standing, walking, sprinting, crouched, leaning, jumping: ours, unchanged.
-- The flag is cleared on every exit path (state end, death, weapon change, vehicle, mod absent), so a saved
-- game never keeps a stale pose hold.
--
-- Key clashes of its first run (it moves vanilla actions off its keys): "Equip/Turn On/Off Light Source" to Z,
-- which is our ToggleDevice default, and "Melee" to Mouse 5, which is our stance mouse default - announced in
-- the console; TW_StanceMouse leaves an attack button to the game (no accidental shove from a stance press).
local M={}
TWAdvMove=M

M.VARIABLE='TWAdvMoveYield'
M.IDS={'ViewpointAdvancedMovement','ViewpointAdvancedMovementServer','ViewpointClassicCamera'}
-- The bindings Advanced Movement moves on its first run, for the clash line only (it moves them in the game's
-- own keybinds; our defaults are read from our option pages below).
M.MOVED={LightSource='Equip/Turn On/Off Light Source',Melee='Melee'}

local active=false
local reported=false
local checkedAt=0
local CHECKS_MS=5000
local flags=setmetatable({},{__mode='k'}) -- player -> last value we wrote
local last=nil

local function log(s) print('[TW-ADVMOVE] '..s) end

local function strip(id)
    id=tostring(id or '')
    while string.sub(id,1,1)=='\\' do id=string.sub(id,2) end
    return id
end

-- The mod ids the game has activated (B42 can prefix them with a backslash).
local function mods()
    local found={}
    if type(getActivatedMods)=='function' then
        local ok=pcall(function()
            local list=getActivatedMods()
            if not list then return end
            for i=0,list:size()-1 do found[strip(list:get(i))]=true end
        end)
        if not ok then found={} end
    end
    return found
end

-- Is the add-on there AND running? Its Java part is what sets the stance variables and poses the body; without
-- it (ZombieBuddy not approved) only its keybinds live, and there is no pose to yield to.
function M.detect()
    local found=mods()
    local running=rawget(_G,'ViewpointMovement')
    local api=type(running)=='table' and type(running.Input)=='table'
    active=found.ViewpointAdvancedMovement==true and api
    return active
end

function M.active() return active and M.enabled end

-- QA switch (TWAdvMove.setEnabled), never an option: the same build can show the frames without the
-- hand-over (the first-person pose over a lying body) next to the frames with it.
M.enabled=true

function M.apiVersion()
    local running=rawget(_G,'ViewpointMovement')
    local api=type(running)=='table' and running.Input or nil
    if not (api and type(api.version)=='function') then return nil end
    local ok,version=pcall(api.version)
    return ok and tonumber(version) or nil
end

local function boolVar(p,name)
    if not (p and type(p.getVariableBoolean)=='function') then return false end
    local ok,value=pcall(p.getVariableBoolean,p,name)
    return ok and value==true
end

local function textVar(p,name)
    if not (p and type(p.getVariableString)=='function') then return false end
    local ok,value=pcall(p.getVariableString,p,name)
    return ok and type(value)=='string' and value~=''
end

local localPlayer -- forward: the prone API is the local player's own

-- Advanced Movement's own Lua answer for the local player (getting down, prone, rolling, getting up); the
-- animation variables cover everyone else (they are set on received remote poses too) and the exact phase.
local function proneActive(p)
    if localPlayer(p) then
        local running=rawget(_G,'ViewpointMovement')
        local api=type(running)=='table' and running.Input or nil
        if api and type(api.isProne)=='function' then
            local ok,value=pcall(api.isProne)
            if ok and value==true then return true end
        end
    end
    return boolVar(p,'VPAM_Prone') or boolVar(p,'VPAM_ProneRise')
end

local function steadyProne(p)
    if not boolVar(p,'VPAM_Prone') then return false end
    return not (boolVar(p,'VPAM_ProneRise') or boolVar(p,'VPAM_RollToBack') or boolVar(p,'VPAM_RollToFront')
        or boolVar(p,'VPAM_SideRollLeft') or boolVar(p,'VPAM_SideRollRight'))
end

-- Aiming as the pose layer itself reads it (TWZB.aimIntent falls back to IsoGameCharacter.isAiming).
local function aiming(p)
    local z=rawget(_G,'TWZB')
    if type(z)=='table' and type(z.aimIntent)=='function' then
        local ok,value=pcall(z.aimIntent,p)
        if ok and type(value)=='boolean' then return value end
    end
    if p and type(p.isAiming)=='function' then
        local ok,value=pcall(p.isAiming,p)
        return ok and value==true
    end
    return false
end

localPlayer=function(p)
    if not p then return false end
    if type(instanceof)=='function' then
        local ok,value=pcall(instanceof,p,'IsoPlayer')
        if not ok or value~=true then return false end
    end
    if type(p.isLocalPlayer)=='function' then
        local ok,value=pcall(p.isLocalPlayer,p)
        return ok and value==true
    end
    return false
end

local function inVehicle(p)
    if not (p and type(p.getVehicle)=='function') then return false end
    local ok,value=pcall(p.getVehicle,p)
    return ok and value~=nil
end

local function dead(p)
    if not (p and type(p.isDead)=='function') then return false end
    local ok,value=pcall(p.isDead,p)
    return ok and value==true
end

-- One frame's facts (kept pure so the gate can check the whole table).
function M.facts(p)
    local f={active=active and M.enabled,isLocal=localPlayer(p),alive=not dead(p),vehicle=inVehicle(p)}
    f.climb=textVar(p,'VPAM_Climb')
    f.vault=boolVar(p,'VPAM_WindowVault') or boolVar(p,'VPAM_WindowVaultSprint')
    f.prone=proneActive(p)
    f.steadyProne=f.prone and steadyProne(p)
    f.aiming=aiming(p)
    return f
end

-- The whole rule, pure: does the movement add-on's own pose own the arms and weapon this frame?
function M.yieldFor(f)
    if not (f and f.active and f.isLocal and f.alive and not f.vehicle) then return false end
    if f.climb or f.vault then return true end                -- climbing up/over, hanging, window vault: no aiming there
    if not f.prone then return false end                       -- standing, walking, sprinting, crouched, leaning, jumping: ours
    if not f.steadyProne then return true end                  -- getting down, rolling, getting up: no aiming there
    return not f.aiming                                        -- lying still: aiming works, our pose fits the lying body
end

-- Any stance whose pose owns the drawn arms and weapon for ANYONE (local and remote): the body's own weapon IK
-- (TW_GripIK) must not put its standing grip over a lying/climbing clip. The local first-person pose is narrower
-- (yieldFor): lying still and aiming there is ours.
function M.inStance(p)
    local f=M.facts(p)
    return f.active and f.alive and not f.vehicle and (f.climb or f.vault or f.prone)
end

-- What TW_GripIK asks: a remote player yields in every such stance; the local player only where our first-person
-- pose yields too (so prone aiming keeps the hands on our sight) - and only while Viewpoint's first person is the
-- one drawn, because in third person/isometric there is no first-person pose over the prone body.
function M.yielding(p)
    if not (p and type(p.isLocalPlayer)=='function') then return false end
    local ok,localp=pcall(p.isLocalPlayer,p)
    if not ok or localp~=true then return M.inStance(p) end
    local z=rawget(_G,'TWZB')
    local first=false
    if type(z)=='table' and type(z.vpState)=='function' then
        local good,state=pcall(z.vpState)
        first=good and state==1
    end
    if not first then return M.inStance(p) end
    return M.yieldFor(M.facts(p))
end

local function clear(p)
    if p and type(p.clearVariable)=='function' then pcall(p.clearVariable,p,M.VARIABLE) end
end

-- Our AnimSet nodes and the add-on's prone nodes both sit at ConditionPriority 100: on a tie the state machine
-- kept our standing carry/aim clip on the lying body (the third-person QA frame before this hook showed it).
-- Every node we ship is gated by TWGripOwned, so clearing it inside an add-on stance hands the body to the
-- add-on's prone nodes (and to vanilla for a climb); TW_TacHoldOverride.clear sets it again on its own update.
local function hookCarry()
    local T=rawget(_G,'TWTacHoldOverride')
    if type(T)~='table' or type(T.clear)~='function' or T.twAdvGate then return false end
    local clearCarry=T.clear
    T.clear=function(player)
        clearCarry(player)
        if active and M.enabled and player and M.inStance(player) then
            pcall(player.setVariable,player,'TWGripOwned',false)
        end
    end
    T.twAdvGate=true
    return true
end

-- Write/clear the flag our Java layers read (FpPose.bodyState). Only on a change.
local function apply(p)
    local f=M.facts(p)
    local want=M.yieldFor(f)
    local before=flags[p]
    if want then
        if before~=true and type(p.setVariable)=='function' then pcall(p.setVariable,p,M.VARIABLE,true) end
        flags[p]=true
    else
        if before~=false then clear(p) end
        flags[p]=false
    end
    last={player=p,facts=f,yield=want,before=before}
    return want
end

function M.yield(p) return apply(p) end

-- One-time console report, with the key clashes of its first run.
local function keyName(code)
    if not code or code<=0 then return 'none' end
    local ok,name=pcall(getKeyName,code)
    return ok and tostring(name) or tostring(code)
end

local function keyOf(core,name)
    if not (core and type(core.getKey)=='function') then return nil end
    local ok,code=pcall(core.getKey,core,name)
    return ok and tonumber(code) or nil
end

local function ourKey(id)
    local api=type(PZAPI)=='table' and PZAPI.ModOptions or nil
    if not (api and type(api.getOptions)=='function') then return nil end
    local ok,options=pcall(api.getOptions,api,'EscapeFromZomboidFirearms')
    if not ok or not (options and type(options.getOption)=='function') then return nil end
    local found,option=pcall(options.getOption,options,id)
    if not found or not (option and type(option.getValue)=='function') then return nil end
    local good,value=pcall(option.getValue,option)
    return good and tonumber(value) or nil
end

local function clash()
    local core=getCore and getCore()
    if not core then return end
    local melee=keyOf(core,M.MOVED.Melee)
    local light=keyOf(core,M.MOVED.LightSource)
    local notes={}
    if melee==10000+4 then
        notes[#notes+1]='vanilla Melee (moved to Mouse 5) shares the default carry-stance button: the stance press is left to the game, pick another button under Options > Mods or rebind Melee'
    end
    local toggle=ourKey('ToggleDevice')
    if toggle and toggle~=0 and toggle==light then
        notes[#notes+1]='vanilla Light Source (moved to '..keyName(light)..') is on the same key as our ToggleDevice: one press does both, rebind one under Options > Keybinds'
    end
    for _,note in ipairs(notes) do log('key clash: '..note) end
end

local function report()
    if reported or not active then return end
    reported=true
    hookCarry()
    local version=M.apiVersion()
    log('Viewpoint Advanced Movement found'..(version and (' (API '..version..')') or '')
        ..': its climb, hang, window vault and prone transitions take the arms and weapon; lying still and aiming keeps our first-person ADS and ballistics')
    local ok,err=pcall(clash)
    if not ok then log('key clash check failed: '..tostring(err)) end
end

-- Clear a player's flag and forget it (death, leaving, mod gone).
local function forget(p)
    if flags[p] then clear(p) end
    flags[p]=false
    last=nil
end

function M.reset()
    for p in pairs(flags) do clear(p) end
    flags=setmetatable({},{__mode='k'})
    active=false
    checkedAt=0
    last=nil
end

-- QA only: off reproduces the unfixed behaviour for before/after frames. Clears the flag at once.
function M.setEnabled(on)
    M.enabled=on and true or false
    if not M.enabled then
        for p in pairs(flags) do forget(p) end
    end
    return M.enabled
end

function M.stats(p)
    p=p or getSpecificPlayer(0)
    local f=p and M.facts(p) or {}
    return string.format('active=%s enabled=%s api=%s local=%s alive=%s vehicle=%s prone=%s steadyProne=%s climb=%s vault=%s aiming=%s yield=%s flag=%s',
        tostring(active),tostring(M.enabled),tostring(M.apiVersion()),tostring(f.isLocal),tostring(f.alive),tostring(f.vehicle),tostring(f.prone),
        tostring(f.steadyProne),tostring(f.climb),tostring(f.vault),tostring(f.aiming),tostring(f.active and M.yieldFor(f)),
        tostring(p and boolVar(p,M.VARIABLE)))
end

local function tick()
    local now=getTimestampMs and getTimestampMs() or 0
    if now==0 or now-checkedAt>=CHECKS_MS then
        checkedAt=now
        local ok,err=pcall(M.detect)
        if not ok then active=false;log('detection failed: '..tostring(err)) end
    end
    if active then report() end
    if isGamePaused and isGamePaused() then return end
    local n=type(getNumActivePlayers)=='function' and getNumActivePlayers() or 0
    for i=0,n-1 do
        local p=getSpecificPlayer(i)
        if p and p:isLocalPlayer() then
            local ok,err=pcall(apply,p)
            if not ok then log('state failed: '..tostring(err)) end
        end
    end
    if not active then
        for p in pairs(flags) do forget(p) end
    end
end

Events.OnTick.Add(tick)
Events.OnGameStart.Add(function()
    M.reset()
    M.detect()
    local p=getSpecificPlayer(0)
    if p and p:isLocalPlayer() then apply(p) end
end)
Events.OnCreatePlayer.Add(function(_,p)
    M.detect()
    if p and p:isLocalPlayer() then apply(p) end
end)
Events.OnPlayerDeath.Add(function(p) forget(p) end)
-- A weapon change or a vehicle is a new frame of the same rule; recompute at once rather than a tick later.
Events.OnEquipPrimary.Add(function(p) if p and instanceof(p,'IsoPlayer') and p:isLocalPlayer() then apply(p) end end)
Events.OnEquipSecondary.Add(function(p) if p and instanceof(p,'IsoPlayer') and p:isLocalPlayer() then apply(p) end end)

return M
