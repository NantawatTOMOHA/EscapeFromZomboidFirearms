-- Optional diagnostics on the existing ModOptions page. QA overrides are memory-only.
require('TW_ViewpointSensors')
local options=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearms')
options:addTickBox('ViewpointPerf',getText('UI_TW_ViewpointPerf'),false,getText('UI_TW_ViewpointPerf_tooltip'))
local P={qa=false}
TWViewpointPerf=P

function P.update()
    local zb=rawget(_G,'TWZB')
    if type(zb)~='table' or not zb.perfEnable then return end
    local on=P.qa or options:getOption('ViewpointPerf'):getValue()==true
    if P.sent~=on then zb.perfEnable(on);P.sent=on end
    if on and zb.perfTick then zb.perfTick() end
end

Events.OnTickEvenPaused.Add(P.update)
Events.OnGameStart.Add(P.update)
return P
