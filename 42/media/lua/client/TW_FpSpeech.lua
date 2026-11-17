-- Native ChatElement calls bypass Lua class wrappers. The guarded Java hooks
-- keep the original speech/chat path and ask this sink before hiding its draw.
local N=require('TW_SightNotice')
local F={accepted=false}

local function bridge()
    local b=rawget(_G,'TWFPSpeechBridge')
    return type(b)=='table' and b.ready and b.reset and b or nil
end

function F.route(player,message)
    local b=bridge()
    if not b then return false end
    local ok,ready=pcall(b.ready)
    if not ok or ready~=true or not N.active(player) then return false end
    -- A nil message probes the sink without extending or duplicating a notice.
    if message==nil then return true end
    local accepted=N.show(player,message)
    if accepted then F.accepted=true end
    return accepted
end

local function sync()
    local b=bridge()
    if not b then
        if F.accepted then N.clear();F.accepted=false end
        return
    end
    local player=getSpecificPlayer(0)
    local ok,ready=pcall(b.ready)
    if not N.active(player) or (F.accepted and (not ok or ready~=true)) then
        N.clear();pcall(b.reset);F.accepted=false
    end
end

TWFPSpeechRoute=F.route
Events.OnTick.Add(sync)
return F
