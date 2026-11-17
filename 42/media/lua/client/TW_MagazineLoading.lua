-- Loading a loose TW magazine: the vanilla action owns timing, rounds and sounds.
-- Only its display model changes. MagazineLoading.java attaches the loading alias
-- to the actual left hand after all pose layers, in isometric and Viewpoint views.
require('TimedActions/ISLoadBulletsInMagazine')
local D=require('TW/Data')
local Anim=require('TW/AnimOptOut') -- W8 animopt: the two "vanilla animations" options
local M={started=0,fallbacks=0,errors=0,active=setmetatable({},{__mode='k'})}
TWMagazineLoading=M

local function native() return rawget(_G,'TWMagazineLoadingNative') end
local function fullModel(magazine)
    local meta=magazine and D.types[magazine:getFullType()]
    local entry=meta and D.items[meta.tpl]
    if entry and entry.clip then return 'EscapeFromZomboidFirearms.ML_'..meta.tpl end
end
M.modelFor=fullModel
function M.roundFor(action)
    local ammo=action.ammoTypeOverride or action.magazine:getAmmoType():getItemKey()
    if type(ammo)~='string' then return end
    local model='EscapeFromZomboidFirearms.MLA_'..ammo:gsub('%.','_')
    if getScriptManager():getModelScript(model) then return model end
end

local function release(action)
    local state=action.twMagazineLoading
    if not state then return end
    action.twMagazineLoading=nil
    local n=native()
    if n and n.proof then action.twMagazineLoadingProof=n.proof(action.character) end
    if n and n.finish then n.finish(action.character,state.magazine) end
end

-- The primary inventory weapon stays equipped while both hands load a loose
-- magazine. Its grip transfer must not drag the loading hand onto that hidden gun.
function M.isLoading(character) return M.active[character]~=nil end
local function releaseGrip(action)
    if M.active[action.character]==action then M.active[action.character]=nil end
end

local function fallback(action)
    release(action)
    -- Matches vanilla start(), including the empty right hand. No ammunition prop
    -- is authored by this PZ animation; it is the right hand that inserts rounds.
    if not action.loadFinished then action:setOverrideHandModels(nil,'GunMagazine') end
    M.fallbacks=M.fallbacks+1
end

local function begin(action)
    local status=action.twMagazineLoadingStart
    -- W8 animopt (the user's item 9): the magazine alias on the hand is a hand attachment of the mod; with this view's
    -- option on the vanilla hand model stays and the vanilla reload clip plays.
    if not Anim.poses(action.character) then
        if status then status.reason='animations-off' end
        return
    end
    local model=fullModel(action.magazine)
    status.model=model
    if action.ammoCountStart==nil then status.reason='vanilla-start-rejected';return end
    if action.loadFinished then status.reason='already-finished';return end
    if not model then status.reason='other-magazine';return end
    local n=native()
    status.ready=n and n.begin and n.ready and n.ready() or false
    if not status.ready then status.reason='native-unavailable';return end
    status.modelScript=getScriptManager():getModelScript(model)~=nil
    if not status.modelScript then status.reason='model-script-missing';return end
    status.begin=n.begin(action.character,action.magazine,model)
    if not status.begin then status.reason='native-begin-rejected';return end
    local cartridge=M.roundFor(action)
    if not (cartridge and n.selectRound and n.selectRound(action.character,cartridge)) then cartridge=nil end
    action.twMagazineLoading={model=model,magazine=action.magazine,round=cartridge,roundHeld=true}
    action:setOverrideHandModels(cartridge,model)
    status.reason='alias-selected'
    M.started=M.started+1
end

local function guarded(fn,action)
    local ok,err=pcall(fn,action)
    if not ok then
        if action.twMagazineLoadingStart then action.twMagazineLoadingStart.reason='display-error: '..tostring(err) end
        M.errors=M.errors+1
        if M.errors==1 then print('[TW] magazine loading display fallback: '..tostring(err)) end
        pcall(fallback,action)
    end
end

function M.describe(action)
    local state=action and action.twMagazineLoading
    local n=native()
    return state and ('model='..state.model..' '..(n and n.describe and n.describe(action.character) or 'native=missing'))
        or 'model=GunMagazine loadingAlias=false'
end

function M.startInfo(action)
    local status=action and action.twMagazineLoadingStart
    return 'start='..(status and status.reason or 'not-called')
        ..' ammoCountStart='..tostring(action and action.ammoCountStart)
        ..' model='..tostring(status and status.model)
        ..' native.ready='..tostring(status and status.ready)
        ..' modelScript='..tostring(status and status.modelScript)
        ..' begin='..tostring(status and status.begin)
end

function M.install()
    if ISLoadBulletsInMagazine.twMagazineLoadingInstalled then return end
    ISLoadBulletsInMagazine.twMagazineLoadingInstalled=true
    local class=ISLoadBulletsInMagazine
    local start,update,stop,perform=class.start,class.update,class.stop,class.perform
    function class:start()
        self.twMagazineLoadingStart={reason='vanilla-start-entered'}
        local result=start(self)
        if self.ammoCountStart~=nil and not self.loadFinished and fullModel(self.magazine) then
            M.active[self.character]=self
            if TWZB and TWZB.gripIKOff then TWZB.gripIKOff(self.character) end
        end
        guarded(begin,self)
        return result
    end
    function class:update()
        if self.loadFinished then releaseGrip(self) end
        if self.twMagazineLoading then
            guarded(function(action)
                local n=native()
                if not (n and n.ready and n.ready()) then fallback(action)
                elseif n.refresh then
                    n.refresh(action.character)
                    if not n.ready() then fallback(action) end
                end
            end,self)
        end
        -- Vanilla clears the models and completes the action at loadFinished.
        -- Never reapply an alias after this call (the next action may have begun).
        return update(self)
    end
    function class:stop()
        releaseGrip(self)
        guarded(release,self)
        return stop(self)
    end
    function class:perform()
        releaseGrip(self)
        guarded(release,self)
        return perform(self)
    end
end

Events.OnGameBoot.Add(M.install)
Events.OnInitGlobalModData.Add(M.install)
Events.OnGameStart.Add(M.install)
return M
