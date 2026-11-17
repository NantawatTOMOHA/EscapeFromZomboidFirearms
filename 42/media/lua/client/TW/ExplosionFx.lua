-- Public cosmetic API shared by launchers and future hand grenades.
-- XY world cells, Z levels; damage/sound/networking remain with their owner.
local M={lastError=nil}
-- Performance pass 23.10.2026: modules required once, where they were required before (a require in PZ
-- builds its path and scans every loaded file each call; OnPostRender ran it every frame).
local Muzzle,ImpactNet
local function invoke(name,args)
    local zb=rawget(_G,'TWZB')
    local api=rawget(_G,'ExplosionFx')
    if not zb or not api or not api[name] then return nil end
    local ok,value=pcall(function()
        Muzzle=Muzzle or require('TW/MuzzleFx')
        Muzzle.ensureData(zb)
        return api[name](args)
    end)
    if ok then return value end
    if M.lastError~=tostring(value) then M.lastError=tostring(value);print('[TW-EXPLOSION] API error '..M.lastError) end
end
function M.explode(args) return invoke('explode',args) end
function M.smokeStart(args) return invoke('smokeStart',args) end
function M.smokeStop(id) invoke('smokeStop',id) end
function M.install()
    local api=rawget(_G,'ExplosionFx')
    local ok,ready=false,false
    if api and api.available then ok,ready=pcall(api.available) end
    if ok and ready and api.reset then pcall(api.reset) end
    if ok and ready and api.readyImages then pcall(api.readyImages) end
    TWExplosionFx=ok and ready and M or nil
    return ok and ready
end
-- The Viewpoint bridge becomes ready after OnGameStart (w12d: the API stayed nil for the whole
-- session), so a failed install is retried every tick until the bridge is ready.
local retrying=false
local function retry()
    if M.install() then Events.OnTick.Remove(retry);retrying=false end
end
Events.OnGameStart.Add(function()
    if not M.install() and not retrying then retrying=true;Events.OnTick.Add(retry) end
end)
if Events.OnPostRender then Events.OnPostRender.Add(function()
    ImpactNet=ImpactNet or require('TW/ImpactNet')
    if not ImpactNet.isometric() then return end
    local api=rawget(_G,'ExplosionFx')
    if api and api.isometric then api.isometric() end
end) end
return M
