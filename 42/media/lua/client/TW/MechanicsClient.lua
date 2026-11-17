local M=require('TW/Mechanics')
if not M.active then return end
local Client=require('WeaponSystems/Client')
-- In multiplayer Gunworks sends the selected mode without running the shared
-- recoil update locally. Persist the choice before a same-tick ammo restore.
local update=Client.OnPlayerUpdateFiremode
Client.OnPlayerUpdateFiremode=function(player,weapon,mode,...)
    M.apply(weapon,mode,player)
    return update(player,weapon,mode,...)
end
