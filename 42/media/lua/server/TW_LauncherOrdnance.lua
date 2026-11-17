-- Both SP and the MP server execute this file. Spend a disposable tube only
-- after the existing Gunworks projectile spawn succeeds. Keep the HandWeapon
-- equipped through the complete native attack callback: its later tracer lookup
-- reads player:getAttackingWeapon(), not the local weapon argument.
local L=require('TW/Launchers')
local installed=false
local pending={}
local function finishPending()
    if isClient() then return end
    local ready=pending
    pending={}
    for _,row in ipairs(ready) do
        -- This runs after the attack callback unwinds, in SP and on the server.
        -- L.spend keeps a newly equipped item untouched and uses the old tube's
        -- current container; only the authoritative side replaces inventory.
        L.spend(row.player,row.weapon)
    end
end
Events.OnTick.Add(finishPending)
local function install()
    if installed or not L.config.enabled or not L.config.disposable then return end
    require('ExplosivesSystems/Server')
    local E=require('ExplosivesSystems/Physics')
    local spawn=E.doSpawnOrdnance
    E.doSpawnOrdnance=function(player,source,ox,oy,oz,dx,dy,dz,isAmmo)
        if source~=L.config.disposable.ammo or not isAmmo then
            return spawn(player,source,ox,oy,oz,dx,dy,dz,isAmmo)
        end
        local weapon=player and player:getPrimaryHandItem()
        if not L.isDisposable(weapon) then return end
        local state=weapon:getModData().TWDisposable
        if not state or not state.initialized or state.spent or state.pending then return end
        local before=E.nextOrdnanceId
        local result=spawn(player,source,ox,oy,oz,dx,dy,dz,isAmmo)
        if E.nextOrdnanceId~=before then
            -- Guard duplicate packets immediately, without changing the native
            -- attacking weapon or its enum inside OnWeaponSwingHitPoint.
            state.pending=true
            pending[#pending+1]={player=player,weapon=weapon}
        end
        return result
    end
    installed=true
end
Events.OnGameBoot.Add(install)
Events.OnGameStart.Add(install)
