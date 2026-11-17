-- Visual-only launcher routing. Ammo, queues, speed and event handling stay
-- with the ordinary PZ/Gunworks timed actions. No state is stored on items.
local L=require('TW/Launchers')
local Anim=require('TW/AnimOptOut') -- W8 animopt: the two "vanilla animations" options
local M={}
local TYPES={
 ['EscapeFromZomboidFirearms.FN40GL']='fn40gl',
 ['EscapeFromZomboidFirearms.MSGL']='m32a1',
 ['EscapeFromZomboidFirearms.GL_62e7e7bbe6da9612f743f1e0']='gp25',
 ['EscapeFromZomboidFirearms.GL_5648b62b4bdc2d9d488b4585']='gp34',
 ['EscapeFromZomboidFirearms.GL_6357c98711fb55120211f7e1']='m203',
}
M.states=setmetatable({},{__mode='k'})

function M.kind(gun)
 if not L.config.enabled or not gun then return nil end
 local kind=TYPES[gun:getFullType()]
 if kind=='gp25' or kind=='gp34' or kind=='m203' then
  return L.hostKey(gun) and kind or nil
 end
 return kind
end

function M.round(gun)
 return tostring(math.max(0,math.min(5,math.floor(gun:getCurrentAmmoCount()))))
end

local function set(player,state,key,value)
 if state[key]~=value then player:setVariable(key,value);state[key]=value end
end

function M.clear(player)
 if not player then return end
 if M.states[player] then
  for _,name in ipairs({'TWLauncherKind','TWLauncherRound','TWLauncherFirst'}) do player:clearVariable(name) end
  M.states[player]=nil
 end
end

-- Called for local and remote characters. An observer derives the round from
-- authoritative gun ammo and the replicated action flags, never from a timer.
function M.update(player)
 if not player then return end
 -- W8 animopt (the user's item 9): with this view's animations given to vanilla, no launcher clip variables and no
 -- break-action selector of ours (a set from before the switch is cleared).
 if not Anim.poses(player) then M.clear(player);return end
 local gun=player:getPrimaryHandItem()
 local kind=M.kind(gun)
 if not kind then M.clear(player);return end
 local state=M.states[player]
 if not state or state.gun~=gun then
  M.clear(player);state={gun=gun};M.states[player]=state
 end
 set(player,state,'TWLauncherKind',kind)
 local loading=player:getVariableBoolean('isLoading')
 if kind=='m32a1' then
  if not state.action then
   if loading and not state.loading then state.initial=gun:getCurrentAmmoCount() end
   set(player,state,'TWLauncherRound',M.round(gun))
   set(player,state,'TWLauncherFirst',loading and state.initial==gun:getCurrentAmmoCount())
  end
  state.loading=loading
 elseif player:getVariableBoolean('isUnloading') then
  -- Current Gunworks sets its custom breakaction selector for load/rack only.
  -- Route unload through the same existing breakaction contract too, keeping
  -- openWeapon rather than the vanilla shotgun sprite replacement event.
  if player:getVariableString('WeaponReloadType')~='breakaction' then
   player:setVariable('WeaponReloadType','breakaction')
  end
 end
end

function M.begin(action,which)
 local player,gun=action.character,action.gun
 -- W8 animopt: the launcher's vanilla reload/unload clips play (no TWL nodes, no break-action override).
 if not Anim.poses(player) then return end
 local kind=M.kind(gun)
 if not kind then return end
 M.update(player)
 local state=M.states[player]
 state.action=action
 state.initial=gun:getCurrentAmmoCount()
 action.twLauncherKind=kind
 action.twLauncherWhich=which
 if kind=='m32a1' and which=='load' then
  set(player,state,'TWLauncherRound',M.round(gun))
  set(player,state,'TWLauncherFirst',true)
 elseif kind~='m32a1' then
  action:setAnimVariable('WeaponReloadType','breakaction')
 end
end

function M.afterRound(action)
 if action.twLauncherKind~='m32a1' or action.twLauncherWhich~='load' then return end
 local state=M.states[action.character]
 if not state or state.action~=action then return end
 -- On an MP client the event precedes the server's ammo replication. Advance
 -- only this visual index; reading the still-old native count would replay the
 -- same chamber forever while state.action deliberately freezes tick routing.
 local count=action.gun:getCurrentAmmoCount()
 local nextRound=math.max(count,(tonumber(state.TWLauncherRound) or count)+1)
 set(action.character,state,'TWLauncherRound',tostring(math.max(0,math.min(5,nextRound))))
 set(action.character,state,'TWLauncherFirst',false)
end

function M.prop(action,show,tag)
 if not action.twLauncherKind then return end
 -- W8 animopt: no shell/round in the hand while this view's animations are vanilla.
 if not Anim.poses(action.character) then action:setOverrideHandModels(action.gun,nil);return end
 if tag then
  local kind,player=action.twLauncherKind,action.character
  local which
  if player:getVariableBoolean('isUnloading') then which=kind=='m32a1' and 'Dump' or 'Unload'
  elseif player:getVariableBoolean('isRacking') then which=kind=='m32a1' and 'Close' or 'Rack'
  elseif kind=='m32a1' then
   local state=M.states[player]
   which=(state and state.TWLauncherFirst and 'First' or 'Round')..(state and state.TWLauncherRound or M.round(action.gun))
  else which='Load' end
  -- A blended-out clip can deliver its hide after the next chamber's show.
  if tag~=kind..':'..which then return end
 end
 if not show then action:setOverrideHandModels(action.gun,nil);return end
 local ammo=action.gun:getAmmoType()
 local key=ammo and ammo:getItemKey()
 local tpl=key and key:match('%.A_(%x+)$')
 if tpl then
  local model=require('TW/ReloadVisual').handModel(tpl)
  if model then action:setOverrideHandModels(action.gun,model) end
 end
end

function M.finish(action)
 if not action.twLauncherKind then return end
 M.prop(action,false)
 local state=M.states[action.character]
 if state and state.action==action then
  state.action=nil;state.initial=nil;state.loading=false
  set(action.character,state,'TWLauncherFirst',false)
 end
 action.twLauncherKind=nil;action.twLauncherWhich=nil
end

return M
