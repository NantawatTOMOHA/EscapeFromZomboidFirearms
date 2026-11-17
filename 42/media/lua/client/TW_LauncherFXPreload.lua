-- Request the sixteen short-lived effect models before the first explosion.
-- The native loader shares the same mesh/texture/static/basicEffect cache key
-- as Render3DItem. This requests assets; it does not delay or replay an effect.
local config=require('TW/Data/Launchers')
if isServer() or not config.enabled then return end

local requested=false
Events.OnGameStart.Add(function()
    if requested then return end
    for frame=0,15 do
        loadStaticZomboidModel('TW_FXPreload_'..frame,'TW/explosion','TW/fx/frame_'..frame)
    end
    requested=true
end)
