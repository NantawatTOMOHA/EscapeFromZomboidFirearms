-- The local shooter's one frame of visible and physical aim; no random spread.
require('TW_Tactical')
local R=require('TW/Runtime')
local M={errors=0}
TWPanicSway=M
local options=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearms')
options:addTickBox('PanicSway',getText('UI_TW_PanicSway'),true,getText('UI_TW_PanicSway_tooltip'))
options:addSlider('PanicSwayStrength',getText('UI_TW_PanicSwayStrength'),0,2,.25,1)
function M.snapshot(p)
    local api=rawget(_G,'PanicSway')
    return api and api.snapshot and api.snapshot(p) or {yaw=0,pitch=0,multiplier=0,stress=0,panic=0}
end
local function update(p)
    local api=rawget(_G,'PanicSway')
    if not (api and api.update and p and p:isLocalPlayer()) then return end
    local on=options:getOption('PanicSway'):getValue()==true and not p:isDead() and R.isWeapon(p:getPrimaryHandItem())
    local strength=options:getOption('PanicSwayStrength'):getValue()
    local moods=p:getMoodles()
    local stress=moods:getMoodleLevel(MoodleType.STRESS)
    local panic=moods:getMoodleLevel(MoodleType.PANIC)
    api.update(p,stress,panic,strength,on,getGameTime():getRealworldSecondsSinceLastUpdate())
end
Events.OnPlayerUpdate.Add(function(p)
    local ok,why=pcall(update,p)
    if not ok then
        M.errors=M.errors+1
        local api=rawget(_G,'PanicSway');if api and api.off then api.off(p) end
        if M.lastError~=tostring(why) then M.lastError=tostring(why);print('[TW-PANIC-SWAY] off '..M.lastError) end
    end
end)
local function reset() local api=rawget(_G,'PanicSway');if api and api.reset then api.reset() end end
Events.OnGameStart.Add(reset)
if Events.OnMainMenuEnter then Events.OnMainMenuEnter.Add(reset) end
return M
