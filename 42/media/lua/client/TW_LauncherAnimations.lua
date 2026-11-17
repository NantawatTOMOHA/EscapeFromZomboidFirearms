-- Launcher-only wrappers installed after Gunworks and the existing action
-- guards. Every original method/event is called once with its original args.
require('TimedActions/ISReloadWeaponAction')
require('TimedActions/ISUnloadBulletsFromFirearm')
require('TimedActions/ISRackFirearm')
local M=require('TW/LauncherAnimations')
TWLauncherAnimations=M

local function wrap(class,which)
 if not class or class.twLauncherAnimations then return end
 class.twLauncherAnimations=true
 local start,event=class.start,class.animEvent
 function class:start(...)
  local result=start(self,...)
  M.begin(self,which)
  return result
 end
 function class:animEvent(name,parameter)
  if name=='TWLPropShow' then M.prop(self,true,parameter)
  elseif name=='TWLPropHide' then M.prop(self,false,parameter) end
  local result=event(self,name,parameter)
  if name=='loadFinished' then M.afterRound(self) end
  return result
 end
 for _,method in ipairs({'stop','perform'}) do
  local prior=class[method]
  class[method]=function(self,...)
   M.finish(self)
   return prior(self,...)
  end
 end
end

local function install()
 wrap(ISReloadWeaponAction,'load')
 wrap(ISUnloadBulletsFromFirearm,'unload')
 wrap(ISRackFirearm,'rack')
end
local due=0
local function observers()
 if not isClient() then return end
 local now=getTimestampMs()
 if now<due then return end
 due=now+100
 local players=getOnlinePlayers()
 if players then
  for i=0,players:size()-1 do
   local player=players:get(i)
   if not player:isLocalPlayer() then M.update(player) end
  end
 end
end
Events.OnGameStart.Add(function() install();due=0 end)
Events.OnPlayerUpdate.Add(M.update)
Events.OnTick.Add(observers)
Events.OnEquipPrimary.Add(M.clear)
Events.OnPlayerDeath.Add(M.clear)
return M
