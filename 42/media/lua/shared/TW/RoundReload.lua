-- Round-by-round reloads at Tarkov's own tempo (bug wave 324, item 1, 24.10.2026). User: "У Винтовки Мосина крайне
-- быстрая скорость перезарядки" (the Mosin reloads extremely fast) - and the whole class: the Mosin and SKS boxes, the
-- tube shotguns, the revolvers, the break-action doubles. They load through vanilla ISReloadWeaponAction, one round per
-- loadFinished of the node's animation (Gunworks AmmoList path); the grip-class nodes played vanilla 0.7 s clips, the
-- Mosin's non-looped with an early-out (0.7 / ReloadSpeed - 0.25 s a round), and the dedicated server emulated
-- vanilla's fixed 590 / 833 / 950 / 2500 ms. Now every round takes its weapon's Tarkov time (TW/Data/RoundReload.lua,
-- tools/round_reload.py: the loop clip over its state speed), PZ's ReloadSpeed (Reloading, panic, straps) on top:
--   client   TWRoundKind selects the *_TWRound_* nodes (priority over the grip-class nodes): Tarkov's own round-load
--            clips of every family (tools/round_reload.py; the tube shotguns, the MTs-255, the revolvers and the doubles
--            since GripIK releases the support hand on them), the first round with Tarkov's RELOAD START before it
--            (TWRoundFirst), the doubles' load with the spent shells thrown out first (TWRoundSpent) and their close
--            (rack); the pistol revolvers' clips stand on no grip class, their nodes pick the body by TWRoundFemale.
--            A class a family's weapons cannot hold plays the fallback node: the class node's vanilla clip at
--            TWRoundSpeed = ReloadSpeed * clip / time (TWRoundRackSpeed the doubles' close).
--   server   serverStart schedules loadFinished on the same times (vanilla emulateAnimEvent / emulateAnimEventOnce).
--   remote   MP observers derive the variables from the replicated isLoading and ammo count (as TW/LauncherAnimations).
-- The round shows in the loading hand between the clip's TWRoundPropShow / TWRoundPropHide (the ammo item's model).
-- Ammo, queues and the server's authority stay vanilla / Gunworks; no state is stored on items.
pcall(require,'TimedActions/ISReloadWeaponAction') -- absent in the offline runtime mocks (tools/check_runtime.py)
local Anim=require('TW/AnimOptOut') -- W8 animopt: the two "vanilla animations" options
local M={}
TWRoundReload=M
local DATA
M.observed=setmetatable({},{__mode='k'})

local function data()
    if DATA==nil then
        local ok,d=pcall(require,'TW/Data/RoundReload')
        DATA=ok and type(d)=='table' and d.weapons or {}
    end
    return DATA
end

-- The weapon's row while it reloads round by round (a Mosin / SKS with a detachable magazine goes through Gunworks).
function M.info(gun)
    if not gun or not gun.getFullType then return nil end
    local row=data()[gun:getFullType()]
    if not row or tostring(gun:getWeaponReloadType())~=row.reload then return nil end
    return row
end

local function reloadSpeed(character)
    local s=character:getVariableFloat('ReloadSpeed',1)
    return (s and s>0) and s or 1
end

-- Fallback node speed: the vanilla clip (row.clip seconds) over ms at this ReloadSpeed.
function M.speed(character,clip,ms)
    return reloadSpeed(character)*clip*1000/ms
end

local function phase(character,row,first)
    character:setVariable('TWRoundSpeed',M.speed(character,row.clip,first and row.firstMs or row.roundMs))
end

function M.begin(action,spent)
    local row=M.info(action.gun)
    if not row or not action.bullets then return end -- no rounds: vanilla start has already stopped it
    local character=action.character
    action.twRound=row
    -- W8 animopt (the user's item 9): with "vanilla animations" on in this view, the vanilla round-load node plays: no
    -- Tarkov round kind, first-round or spent-shell selection and no class speed of ours. Timing follows the clip.
    if not Anim.poses(character) then return end
    action:setAnimVariable('TWRoundKind',row.family)
    -- the pistol revolvers' Tarkov clips stand on no grip class of the mod: their nodes pick the body by this
    action:setAnimVariable('TWRoundFemale',character:isFemale())
    if row.loadMs then
        action.twRoundSpent=spent==true
        action:setAnimVariable('TWRoundSpent',action.twRoundSpent) -- Tarkov's load with the fired shells thrown out first
        character:setVariable('TWRoundSpeed',M.speed(character,row.clip,spent and row.loadSpentMs or row.loadMs))
        character:setVariable('TWRoundRackSpeed',M.speed(character,row.rackClip,row.closeMs))
    else
        action.twRoundFirst=row.firstMs~=row.roundMs
        phase(character,row,action.twRoundFirst)
        action:setAnimVariable('TWRoundFirst',action.twRoundFirst)
    end
end

local function hideProp(action)
    if action.twRoundProp then action.twRoundProp=nil;action:setOverrideHandModels(action.gun,nil) end
end

-- The client's own loadFinished: the next rounds play the loop (TWRoundFirst false). An MP client loads nothing here
-- (the server does); a double still plays its close there, visually (vanilla closes it only in single player).
function M.afterRound(action)
    local row=action.twRound
    if not row then return end
    hideProp(action)
    if row.loadMs then
        if isClient() and action.gun:needToBeClosedOnceReload() then
            action.twRoundClientClose=true
            action:setAnimVariable('isLoading',false)
            action:setAnimVariable('isRacking',true)
        end
    elseif action.twRoundFirst then
        action.twRoundFirst=false
        phase(action.character,row,false)
        action:setAnimVariable('TWRoundFirst',false)
    end
end

-- Bullet type -> the first Tarkov round (A_<tpl>) of its calibre (TW/Data/Ammo rounds and aliases).
local calibreRound
local function calibreTpl(key)
    if not calibreRound then
        calibreRound={}
        local ok,ammo=pcall(require,'TW/Data/Ammo')
        for _,c in pairs(ok and type(ammo)=='table' and ammo.calibres or {}) do
            local tpl
            for _,r in ipairs(c.rounds or {}) do tpl=tpl or tostring(r.type):match('%.A_(%x+)$') end
            if tpl then
                for _,r in ipairs(c.rounds or {}) do calibreRound[r.type]=calibreRound[r.type] or tpl end
                for _,a in ipairs(c.aliases or {}) do calibreRound[a]=calibreRound[a] or tpl end
            end
        end
    end
    return calibreRound[key]
end

-- The round's model in the hand (bug wave 324: only Tarkov rounds A_<tpl> had one, so the calibres' standard rounds
-- - EscapeFromZomboidFirearms.Bullets762x54R, vanilla Base.ShotgunShells - loaded with an empty hand): a Tarkov round,
-- else the item's own Tarkov world model, else the first Tarkov round of its calibre.
function M.roundModel(action)
    local bullet=action.bullets and not action.bullets:isEmpty() and action.bullets:get(0)
    local key=bullet and bullet:getFullType() or (action.gun:getAmmoType() and action.gun:getAmmoType():getItemKey())
    if not key then return nil end
    key=tostring(key)
    local tpl=key:match('%.A_(%x+)$')
    if not tpl then
        local ok,model=pcall(function()
            local item=getScriptManager():FindItem(key)
            return item and item:getWorldStaticModel()
        end)
        tpl=ok and model and tostring(model):match('^EscapeFromZomboidFirearms%.M_(%x+)$') or nil
    end
    tpl=tpl or calibreTpl(key)
    return tpl and require('TW/ReloadVisual').handModel(tpl) or nil
end

-- The node's tag is family:action; the doubles' shells ride in the hand of their load (Load / LoadSpent), bug wave 324.
local function phaseName(action,row)
    if row.loadMs then return action.twRoundSpent and 'LoadSpent' or 'Load' end
    return action.twRoundFirst and 'Open' or 'Loop'
end

function M.prop(action,show,tag)
    local row=action.twRound
    if not row then return end
    -- W8 animopt: no round model in the hand while this view's animations are vanilla; a prop already shown is cleared.
    if not Anim.poses(action.character) then hideProp(action);return end
    -- A blended-out first-round clip can deliver its hide after the loop's show.
    if tag and tag~=row.family..':'..phaseName(action,row) then return end
    if not show then hideProp(action);return end
    local model=M.roundModel(action)
    if model then action.twRoundProp=true;action:setOverrideHandModels(action.gun,model) end
end

function M.finish(action)
    if not action.twRound then return end
    hideProp(action)
    action.character:clearVariable('TWRoundSpeed')
    action.character:clearVariable('TWRoundRackSpeed')
    action.twRound=nil
end

-- Dedicated server: vanilla serverStart's body with the weapon's times (ms over ReloadSpeed). A first round longer
-- than the loop needs several one-shot events (the emulator repeats one period); the emulator drops the rest when the
-- action completes. The doubles' load and close end in one loadFinished (vanilla completes the action there).
function M.serverStart(action)
    local row=M.info(action.gun)
    if not row then return false end
    local gun=action.gun
    local spent=gun:getSpentRoundCount()>0 or gun:isSpentRoundChambered()
    action:initVars()
    if isServer() then
        action:ejectSpentRounds()
        if not action.bullets then action.netAction:forceComplete() end
    end
    local function after(ms) return ISReloadWeaponAction.getReloadTime(action.character,ms) end
    if row.loadMs then
        emulateAnimEventOnce(action.netAction,after((spent and row.loadSpentMs or row.loadMs)+row.closeMs),'loadFinished',nil)
    elseif row.firstMs==row.roundMs then
        emulateAnimEvent(action.netAction,after(row.roundMs),'loadFinished',nil)
    else
        for i=0,math.max(1,gun:getMaxAmmo())-1 do
            emulateAnimEventOnce(action.netAction,after(row.firstMs+i*row.roundMs),'loadFinished',nil)
        end
    end
    return true
end

-- MP observers: a remote player's reload animates on this client from the replicated isLoading and ammo count.
function M.observe(player)
    local gun=player:getPrimaryHandItem()
    local row=M.info(gun)
    -- W8 animopt: a remote body drawn with vanilla animations gets no round variables of ours (a stale set is cleared).
    local anim=Anim.poses(player)
    local loading=anim and row and player:getVariableBoolean('isLoading')
    local state=M.observed[player]
    if not loading then
        if state then
            M.observed[player]=nil
            for _,name in ipairs({'TWRoundKind','TWRoundFirst','TWRoundSpent','TWRoundFemale','TWRoundSpeed','TWRoundRackSpeed'}) do player:clearVariable(name) end
        end
        return
    end
    if not state or state.gun~=gun then
        state={gun=gun,ammo=gun:getCurrentAmmoCount(),first=row.firstMs~=nil and row.firstMs~=row.roundMs}
        M.observed[player]=state
        player:setVariable('TWRoundKind',row.family)
        player:setVariable('TWRoundFemale',player:isFemale() and 'true' or 'false')
        if row.loadMs then
            player:setVariable('TWRoundSpent','false')
            player:setVariable('TWRoundSpeed',M.speed(player,row.clip,row.loadMs))
            player:setVariable('TWRoundRackSpeed',M.speed(player,row.rackClip,row.closeMs))
        else
            phase(player,row,state.first)
            player:setVariable('TWRoundFirst',state.first and 'true' or 'false')
        end
    elseif state.first and gun:getCurrentAmmoCount()>state.ammo then
        state.first=false
        phase(player,row,false)
        player:setVariable('TWRoundFirst','false')
    end
end

local due=0
local function observers()
    local now=getTimestampMs()
    if now<due then return end
    due=now+100
    local players=getOnlinePlayers()
    if not players then return end
    for i=0,players:size()-1 do
        local player=players:get(i)
        if not player:isLocalPlayer() then M.observe(player) end
    end
end

-- Wrapped once, after Gunworks and the mod's launcher wrappers (every original runs once with its own arguments).
function M.install()
    local class=ISReloadWeaponAction
    if not class or class.twRoundReload then return end
    class.twRoundReload=true
    local start,animEvent,serverStart=class.start,class.animEvent,class.serverStart
    function class:start(...)
        local gun=self.gun
        local spent=gun and (gun:getSpentRoundCount()>0 or gun:isSpentRoundChambered())
        local result=start(self,...)
        M.begin(self,spent)
        return result
    end
    function class:animEvent(event,parameter)
        if event=='TWRoundPropShow' then M.prop(self,true,parameter)
        elseif event=='TWRoundPropHide' then M.prop(self,false,parameter)
        elseif event=='rackingFinished' and self.twRoundClientClose then return end -- the server completes it
        local result=animEvent(self,event,parameter)
        if event=='loadFinished' then M.afterRound(self) end
        return result
    end
    function class:serverStart(...)
        if M.serverStart(self) then return end
        return serverStart(self,...)
    end
    for _,method in ipairs({'stop','perform'}) do
        local prior=class[method]
        class[method]=function(self,...)
            M.finish(self)
            return prior(self,...)
        end
    end
    if isClient() then Events.OnTick.Add(observers) end
end

Events.OnGameBoot.Add(M.install)
return M
