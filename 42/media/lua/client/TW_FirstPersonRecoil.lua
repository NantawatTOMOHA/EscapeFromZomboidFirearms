-- Tarkov recoil, sway, breath and arm stamina in Viewpoint's first person (FpRecoil.java through
-- ZombieBuddy, VP-W7-R): every tick each local player holding an own weapon hands Java the
-- hold-breath key (ModOptions; CapsLock by default - no binding of vanilla B42, Viewpoint or this
-- mod uses it), the rounds in the magazine and the chamber, the actual cartridge's template, whether
-- the fire mode is automatic or a burst, the option and the pause. The weapon's parts, grip offset
-- and rate of fire go with the pose request (TW_FirstPersonPose). Arms and breath are a transient
-- local state of Java: never saved, never sent; PZ's own endurance is not touched. Without
-- Viewpoint Java keeps nothing (the poses, the aim time and the camera as before).
require('TW_Tactical')
require('TW_SightInput')
local R=require('TW/Runtime')
local C=require('TW/Core')
local Combo=require('TW_KeyCombo')
local options=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearms')
options:addTickBox('FpRecoil',getText('UI_TW_FpRecoil'),true,getText('UI_TW_FpRecoil_tooltip'))
options:addKeyBind('HoldBreath',getText('UI_TW_HoldBreath'),Keyboard.KEY_CAPITAL)
options:addDescription(getText('UI_TW_HoldBreathHelp'))

local M={sent={},errors=0,lastError=''}
TWFirstPersonRecoil=M

-- Native ammunition -> Tarkov cartridge template: our rounds by their type, a native alias by the
-- standard round of its calibre; '' when unknown (Java then takes the weapon's default cartridge).
local aliases
function M.ammoTpl(gun)
    local at=gun and gun:getAmmoType()
    local kind=at and at:getItemKey()
    if type(kind)~='string' then return '' end
    local tpl=kind:match('^EscapeFromZomboidFirearms%.A_(%x+)$')
    if tpl then return tpl end
    if not aliases then
        aliases={}
        for _,calibre in pairs(C.data.ammo.calibres or {}) do
            local std
            for _,round in ipairs(calibre.rounds or {}) do
                if round.role=='STD' then std=tostring(round.type):match('A_(%x+)$') end
            end
            if std then for _,alias in ipairs(calibre.aliases or {}) do aliases[alias]=std end end
        end
    end
    return aliases[kind] or ''
end

-- An automatic or burst mode (Gunworks RealAuto/RealBurst, vanilla Auto/Burst): a held trigger fires on.
function M.automatic(gun)
    local mode=tostring(gun:getFireMode() or '')
    return mode:find('Auto',1,true)~=nil or mode:find('Burst',1,true)~=nil
end

function M.holding(p)
    if M.qaHold~=nil then return M.qaHold==true end -- QA: the key held without a keyboard
    local pad=rawget(_G,'TWGamepad')
    local joypad=type(p.getJoypadBind)=='function' and p:getJoypadBind() or -1
    if joypad~=-1 then
        if not (pad and pad.held('GamepadHoldBreath',p)) then return false end
    elseif not Combo.held('HoldBreath') then return false end
    local input=rawget(_G,'TWSightInput')
    return not (input and input.blocked and (input.blockedNow or input.blocked)(p))
end

function M.enabled()
    return options:getOption('FpRecoil'):getValue()~=false
end

local function update(p)
    local gun=p:getPrimaryHandItem()
    if not (gun and R.key(gun)) then return end
    local ok,err=pcall(function()
        TWZB.recoilState(p,{hold=M.holding(p),rounds=gun:getCurrentAmmoCount(),chambered=gun:isRoundChambered()==true,
            ammo=M.ammoTpl(gun),auto=M.automatic(gun),enabled=M.enabled(),paused=isGamePaused()==true,
            injury=not (SandboxVars and SandboxVars.EscapeFromZomboidFirearms and SandboxVars.EscapeFromZomboidFirearms.InjuryStates==false)})
    end)
    if not ok then M.errors=M.errors+1;M.lastError=tostring(err) end
    M.sent[p]=true
end

Events.OnTick.Add(function()
    if not (TWZB and TWZB.recoilState) then return end
    for i=0,getNumActivePlayers()-1 do
        local p=getSpecificPlayer(i)
        if p and p:isLocalPlayer() and not p:isDead() then update(p) end
    end
end)

Events.OnPlayerDeath.Add(function(p)
    if TWZB and TWZB.recoilOff and p and M.sent[p] then TWZB.recoilOff(p);M.sent[p]=nil end
end)

-- QA: counters and a player's state.
function M.stats()
    return TWZB and TWZB.recoilStats and TWZB.recoilStats() or 'Java part absent'
end

function M.describe(p)
    return TWZB and TWZB.recoilOf and TWZB.recoilOf(p or getSpecificPlayer(0)) or nil
end

return M
