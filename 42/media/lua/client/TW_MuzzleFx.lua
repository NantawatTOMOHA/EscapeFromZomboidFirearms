-- Tarkov muzzle effects (TW/MuzzleFx): loaded on the client.
require('TW/MuzzleFx')
-- The option "Smoke of shots" (bug wave 324, the user's item 5, 24.10.2026): off, no shot seen by this
-- player emits smoke - the fumes at the muzzle, the smoke of muzzle devices and the barrel's smoke trail
-- (TWZB.muzzleSmoke). The flash, sparks, light and heat stay. A viewer setting, never synchronised.
require('TW_Tactical')
local options=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearms')
options:addTickBox('ShotSmoke',getText('UI_TW_ShotSmoke'),true,getText('UI_TW_ShotSmoke_tooltip'))
options:addTickBox('BarrelHeat',getText('UI_TW_BarrelHeat'),true,getText('UI_TW_BarrelHeat_tooltip'))
options:addTickBox('ShotFlash',getText('UI_TW_ShotFlash'),true,getText('UI_TW_ShotFlash_tooltip'))
options:addTickBox('ShotSparks',getText('UI_TW_ShotSparks'),true,getText('UI_TW_ShotSparks_tooltip'))
options:addTickBox('ShotLight',getText('UI_TW_ShotLight'),true,getText('UI_TW_ShotLight_tooltip'))
options:addTickBox('ImpactParticles',getText('UI_TW_ImpactParticles'),true,getText('UI_TW_ImpactParticles_tooltip'))
options:addTickBox('ImpactDecals',getText('UI_TW_ImpactDecals'),true,getText('UI_TW_ImpactDecals_tooltip'))
local S={}
TWShotSmoke=S

function S.update()
    local zb=rawget(_G,'TWZB')
    local on=options:getOption('ShotSmoke'):getValue()==true
    if type(zb)=='table' then
        if zb.muzzleSmoke and S.sent~=on then zb.muzzleSmoke(on);S.sent=on end
        if zb.muzzleEffects then
            local flash=options:getOption('ShotFlash'):getValue()==true
            local sparks=options:getOption('ShotSparks'):getValue()==true
            local light=options:getOption('ShotLight'):getValue()==true
            local heat=options:getOption('BarrelHeat'):getValue()==true
            local signature=tostring(flash)..tostring(sparks)..tostring(light)..tostring(heat)
            if S.muzzleSent~=signature then zb.muzzleEffects(flash,sparks,light,heat);S.muzzleSent=signature end
        end
    end
    local impact=rawget(_G,'TWImpactFx')
    if type(impact)=='table' and impact.visualOptions then
        local particles=options:getOption('ImpactParticles'):getValue()==true
        local decals=options:getOption('ImpactDecals'):getValue()==true
        local signature=tostring(particles)..tostring(decals)
        if S.impactSent~=signature then impact.visualOptions(particles,decals);S.impactSent=signature end
    end
end

Events.OnTickEvenPaused.Add(S.update)
Events.OnGameStart.Add(S.update)
return S
