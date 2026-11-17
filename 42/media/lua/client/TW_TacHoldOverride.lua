-- CODE ONLY: own low/high/patrol carry. TH is an optional compatibility neighbour.
-- No TH resources, state variables, functions or implementation are required.
-- The carry key cycles low/high/patrol; held it opens the stance menu (TW/HoldMenu).
-- FB3 (user's item 2, 18.10.2026): its default moved from U (vanilla vehicle mechanics and the
-- Gunworks underbarrel toggle) to G (vanilla's only in multiplayer: toggle safety).
-- FB4 (user's items 2 and 8, 19.10.2026): Patrol by default, no "no stance" mode (the vanilla pose);
-- in Viewpoint's first person a shot or the side mouse button raises the weapon to Tarkov's ready
-- only there (M.ready) until a stance is chosen again. A refused stance key says why ([TW-CARRY]),
-- and a stuck state recovers by itself (M.watchdog).
require 'PZAPI/ModOptions'
local R=require('TW/Runtime')
local G=require('TW/Grips')
local RD=require('TW/ReloadData')
local H=require('TW/HoldMenu')
local Anim=require('TW/AnimOptOut') -- W8 animopt: the two "vanilla animations" options
local Combo=require('TW_KeyCombo')
local Carry=assert(G.Carry,'Shared own-carry controller addition is required')
local M={installed=false,approved=setmetatable({},{__mode='k'}),selected=setmetatable({},{__mode='k'}),ready=setmetatable({},{__mode='k'}),
    stale=setmetatable({},{__mode='k'}),sequence=0,session=tostring(getTimestampMs()),recovered=0,refusals=0,lastRefusal=''}
TWTacHoldOverride=M
-- Shown on the controls page of TW_OptionsLayout (FB3); the page id keeps the saved key.
local options=PZAPI.ModOptions:create('EscapeFromZomboidFirearmsCarry','UI_TW_OptionsPageControls')
options:addKeyBind('ReadyToggle',getText('UI_TW_ReadyToggle'),Keyboard.KEY_G)
options:addDescription(getText('UI_TW_CarryHelp'))
-- The user's request of 23.10.2026 (variant A): the mod's stances in the third person can be switched off for
-- animation mods of other authors (Tactical Hold and the like). Off: the body plays the weapon class's own pose
-- without a stance node, the foreign hand masks (RightHandMask, LeftHandMask) stay and Tactical Hold's pose key
-- works for the mod's weapons. Viewpoint's first person of the local player keeps the mod's stances, the key and
-- the menu. A setting of this client: every character it draws follows it, as Tactical Hold's own masks do.
options:addTickBox('ThirdPersonStances',getText('UI_TW_ThirdPersonStances'),true,getText('UI_TW_ThirdPersonStances_tooltip'))
local function integer(value)
 return type(value)=='number' and value==math.floor(value) and value>=0 and value<=2147483647
end
-- QA (A/B in one run): a boolean here overrides the option.
M.thirdPersonOverride=nil
function M.thirdPersonStances()
 if M.thirdPersonOverride~=nil then return M.thirdPersonOverride end
 local option=options:getOption('ThirdPersonStances')
 return option==nil or option:getValue()~=false
end
-- The local player in Viewpoint's first person (as TW_StanceMouse.allowed and M.onShot).
function M.firstPerson(player)
 if not (player and player:isLocalPlayer() and player:getPlayerNum()==0) then return false end
 if not (TWZB and TWZB.vpState and TWZB.vpState()==1) then return false end
 local vp=rawget(_G,'TWViewpoint')
 return vp~=nil and vp.enabled() and not (vp.stt() and vp.giveToStt())
end
-- Whether this client draws the player in the mod's stance.
function M.stancesFor(player)
 return M.thirdPersonStances() or M.firstPerson(player)
end
-- The stance: the saved one (single player, server), the server's answer for a client, the saved one
-- before it comes (older peers without the field: the default), never "no stance".
function M.mode(player)
 if not isClient() then return Carry.modeFor(player,Carry.preference(player))end
 local approved=M.approved[player]
 return Carry.modeFor(player,approved and approved.mode or Carry.preference(player))
end
-- Viewpoint's first person only: the weapon at Tarkov's ready over the stance (after a shot, or by the
-- side mouse button); the stance stays selected and the body keeps it. Not saved, not sent.
function M.isReady(player)
 local r=player and M.ready[player]
 return r~=nil and r.gun==player:getPrimaryHandItem()
end
function M.setReady(player,on,why)
 if not player then return end
 local was=M.isReady(player)
 M.ready[player]=on and {gun=player:getPrimaryHandItem(),why=why,at=getTimestampMs()} or nil
 if was~=(on==true) then M.clear(player) end
end
-- The stance the first-person layer shows (TW_FirstPersonPose): none while ready or not carrying.
-- W10 torchpistol (item 29): a second item in the other hand (flashlight, lantern, bag, umbrella, ...).
-- Two-handed firearms equip the same weapon in both slots (sec == pri), so this is nil for all of them.
function M.offhandItem(player)
 if not player or player:isDead() then return nil end
 local pri,sec=player:getPrimaryHandItem(),player:getSecondaryHandItem()
 if sec~=nil and sec~=pri then return sec end
 return nil
end
function M.fpCarry(player)
 if M.isReady(player) then return '' end
 if player:getVariableBoolean('TWCarryActive') then return M.mode(player) end
 if M.offhandItem(player) and M.firstPerson(player) and Anim.poses(player) and R.visualKey(player:getPrimaryHandItem()) and Carry.canCarry(player) then
  return M.mode(player)
 end
 return ''
end
local function clear(player)
 local offhand=M.offhandItem(player)
 if offhand and R.visualKey(offhand) then R.ensureVisible(offhand) end
 Carry.refreshHandgunRouting(player)
 local gun=player:getPrimaryHandItem()
 local key=not player:isDead() and R.visualKey(gun) or nil
 local owned=key~=nil
 -- W8 animopt (the user's item 9): with "Third person: vanilla animations" on - or "First person" in Viewpoint's
 -- first person - the mod poses nothing in this view. Every AnimSet node the mod ships is gated by TWGripOwned, so
 -- clearing it hands the body back to the vanilla clips and to the other animation mods' nodes; the weapon stays on
 -- the vanilla hand bone. TWCarryMode and the rest stay for the first-person pose, which has its own switch.
 local pose=Anim.poses(player)
 player:setVariable('TWGripOwned',owned and pose)
 player:setVariable('TWGripFemale',owned and pose and player:isFemale())
 if not pose then
  player:clearVariable('TWCarryMode');player:setVariable('TWCarryActive',false)
  player:setVariable('TWCarryRouteReady',false);player:setVariable('TWCarryActionStance',false)
  player:setVariable('TWCarryHandgunActive',false);player:setVariable('TWCarryHandgunRouteReady',false)
  player:setVariable('TWCarryHandgunHeldReady',false);player:clearVariable('TWRackStyle')
  return
 end
 local handgunProfile=owned and Carry.handgunProfile(gun) or nil
 if handgunProfile then player:setVariable('TWCarryHandgunProfile',handgunProfile)
 else player:clearVariable('TWCarryHandgunProfile')end
 player:setVariable('TWCarryHandgunActive',handgunProfile~=nil and offhand==nil and Carry.canCarry(player))
 -- the charging handle style picks the rack clip (tools/reload_clips.py; TW_ReloadVisual)
 local rack=owned and RD.rackWeapons and RD.rackWeapons[key]
 if rack then player:setVariable('TWRackStyle',rack[1]) else player:clearVariable('TWRackStyle') end
 if not owned then
  player:clearVariable('TWCarryMode');player:setVariable('TWCarryActive',false)
  player:setVariable('TWCarryRouteReady',false);player:setVariable('TWCarryActionStance',false)
  return
 end
 if offhand then
  -- W10 torchpistol (item 29): primary hand plays vanilla one-handed Handgun animations while
  -- maskingleft plays the secondary item's LeftHandMask; TWCarryHandgunProfile stays for actions.
  player:clearVariable('RightHandMask')
  player:clearVariable('TWCarryMode');player:setVariable('TWCarryActive',false)
  player:setVariable('TWCarryRouteReady',false);player:setVariable('TWCarryActionStance',false)
  player:setVariable('TWCarryHandgunActive',false);player:setVariable('TWCarryHandgunRouteReady',false)
  player:setVariable('TWCarryHandgunHeldReady',false)
  return
 end
 if not M.stancesFor(player) then
  -- Third-person stances off: no stance node (rifles: the class's base nodes, handguns: the native ones) and
  -- the foreign hand masks stay.
  player:clearVariable('TWCarryMode');player:setVariable('TWCarryActive',false)
  player:setVariable('TWCarryRouteReady',false);player:setVariable('TWCarryActionStance',false)
  player:setVariable('TWCarryHandgunActive',false);player:setVariable('TWCarryHandgunRouteReady',false)
  return
 end
 -- Clear only optional vanilla-mask interference, after foreign startup hooks.
 player:clearVariable('RightHandMask');player:clearVariable('LeftHandMask')
 -- The body plays the class's own clips of the stance; a class without patrol clips its Low Ready.
 player:setVariable('TWCarryMode',Carry.animMode(player,M.mode(player)))
 player:setVariable('TWCarryActive',Carry.canCarry(player))
 local route=Carry.routeReady(player)
 player:setVariable('TWCarryRouteReady',route)
 -- FB5 (the user's item 2): the stance's node stays through the mod's own weapon action and its exit
 player:setVariable('TWCarryActionStance',Carry.actionStance(player,route))
end
-- One route decision for the whole update (TW/Grips Carry.withRoute; performance pass 23.10.2026).
function M.clear(player)
 if not player then return end
 Carry.withRoute(player,clear)
end
-- A stale action variable, reload flag, queue or key press is reset after this long ([TW-CARRY]).
M.STALE_MS=3000
local function quiet(player)
 if not player:getCharacterActions():isEmpty() then return false end
 local queue=ISTimedActionQueue and ISTimedActionQueue.queues and ISTimedActionQueue.queues[player]
 return not (queue and queue.queue and #queue.queue>0)
end
local function recovered(player,what)
 M.recovered=M.recovered+1
 print('[TW-CARRY] recovered: '..what..' (stance key and menu were refused)')
end
-- Every update of a local player: what blocks the stance now and has stayed so without anything
-- running for STALE_MS is reset. (Module-level resets and names: no closures or tables every update.)
local function stuckFor(s,key,stuck,now)
 if not stuck then s[key]=nil;return false end
 s[key]=s[key] or now
 if now-s[key]>=M.STALE_MS then s[key]=nil;return true end
 return false
end
local FLAGS={'isLoading','isRacking','isUnloading'}
function M.watchdog(player)
 if not (player and player:isLocalPlayer()) or player:isDead() then return end
 local now=getTimestampMs()
 local s=M.stale[player] or {}
 M.stale[player]=s
 local actions=player:getCharacterActions():isEmpty()
 local action=player:getVariableString('PerformingAction') or ''
 -- No action or queue changes before the queue recovery below; read this once for all four stale flags.
 local idle=actions and quiet(player)
 if stuckFor(s,'action',idle and (action~='' or player:getVariableBoolean('IsPerformingAnAction')),now) then
  player:clearVariable('PerformingAction');player:setVariable('IsPerformingAnAction',false)
  recovered(player,'action variables without an action ('..action..')')
 end
 for _,name in ipairs(FLAGS) do
  if stuckFor(s,name,idle and player:getVariableBoolean(name),now) then
   player:clearVariable(name);recovered(player,name..' without an action')
  end
 end
 -- A Lua queue whose current action is not running in Java (vanilla resets it in its own tick).
 local queue=ISTimedActionQueue and ISTimedActionQueue.queues and ISTimedActionQueue.queues[player]
 if stuckFor(s,'queue',actions and queue and queue.queue and #queue.queue>0 and not (queue.queue[1] and queue.queue[1].action and player:getCharacterActions():contains(queue.queue[1].action)),now) then
  ISTimedActionQueue.clear(player);recovered(player,'timed action queue without a running action')
 end
 -- A key press whose release never came (focus lost while held): the menu state of that key.
 local state=H.state and H.state.stance
 if stuckFor(s,'press',state and state.pressed and not Combo.held('ReadyToggle'),now) then
  state.pressed,state.opened,state.wasVisible=nil,false,false
  recovered(player,'stance key press without its release')
 end
end
function M.update(player)
 if M.ready[player] and not (M.isReady(player) and TWZB and TWZB.vpState and TWZB.vpState()==1) then
  -- another weapon or another view ends the first-person ready
  M.ready[player]=nil
 end
 M.clear(player)
 pcall(M.watchdog,player)
 if isClient()then
  -- The remote players (local ones run their own update).
  local players=getOnlinePlayers()
  for i=0,players:size()-1 do
   local other=players:get(i)
   if not other:isLocalPlayer() then M.clear(other) end
  end
 end
end
function M.onEquip(player)
 -- Equip events also come for NPCs of other mods (A-Life shells are IsoZombies): players only.
 if not (player and instanceof(player,'IsoPlayer')) then return end
 M.clear(player)
 local gun=player and player:getPrimaryHandItem()
 if not gun or not R.visualKey(gun) then return end
 local profile=G.classFor(gun)
 if profile then player:setVariable('GunworksGripAnim',profile) end
end
local function receive(row)
 if type(row)~='table' or not integer(row.playerId) or not integer(row.generation) or row.generation<1
  or not integer(row.revision) or row.revision<1 or not Carry.validMode(row.mode)
  or type(row.epoch)~='string' or #row.epoch<1 or #row.epoch>40 then return end
 local player=getPlayerByOnlineID(row.playerId)
 if not player then return end -- Periodic Sync recovers late-loaded observers.
 if M.authorityEpoch and M.authorityEpoch~=row.epoch then return end
 M.authorityEpoch=row.epoch
 local prior=M.approved[player]
 if prior and (row.generation<prior.generation or row.generation==prior.generation and row.revision<=prior.revision)then return end
 M.approved[player]={generation=row.generation,revision=row.revision,mode=row.mode}
 local selected=M.selected[player]
 if selected and row.session==M.session and integer(row.sequence) and row.sequence>=selected.sequence then M.selected[player]=nil end
 -- Only the server-approved mode is mirrored into the local owner's save data.
 -- Remote actors use the observation cache; their ModData is never rewritten.
 if player:isLocalPlayer() then Carry.persist(player,row.mode)end
 M.clear(player)
end
Events.OnServerCommand.Add(function(module,command,args)
 if module~=Carry.module or type(args)~='table' then return end
 if command=='State'then receive(args)
 elseif command=='Snapshot'and type(args.states)=='table'and #args.states<=1024 then
  for _,row in ipairs(args.states)do if type(row)=='table'and row.epoch==args.epoch then receive(row)end end
 end
end)
-- Why the stance key may not act now (nil: it may).
function M.refusal(player,key)
 if isGamePaused() then return 'game paused' end
 if not player or not player:isLocalPlayer() then return 'not a local player' end
 if player:isAiming() then return 'aiming' end
 if not M.stancesFor(player) then return 'third-person stances off (options)' end
 if M.offhandItem(player) and not M.firstPerson(player) then return 'secondary hand occupied' end
 -- W8 animopt: the stance key would change an animation this view has given to vanilla.
 if not Anim.poses(player) then return 'weapon animations off (options)' end
 local route=Carry.routeRefusal(player)
 if route then return route end
 if ISChat and ISChat.instance and ISChat.instance.textEntry and ISChat.instance.textEntry:isFocused()then return 'chat text entry focused' end
 local drag=getCell():getDrag(player:getPlayerNum())
 if drag then return 'item placement cursor ('..tostring(drag.Type or drag)..')' end
 if ISTimedActionQueue then
  local queue=ISTimedActionQueue.queues[player]
  if queue and #queue.queue>0 then return 'timed action queued ('..tostring(queue.queue[1] and queue.queue[1].Type)..')' end
 end
 -- Vanilla U opens vehicle mechanics. Preserve that contextual action.
 if getCore():isKey(KeybindId.VEHICLE_MECHANICS,key)and ISVehicleMenu
  and ISVehicleMenu.getVehicleToInteractWith(player)then return 'vehicle mechanics key' end
end
local function keyContext(player,key)
 local why=M.refusal(player,key)
 if why then
  M.refusals=M.refusals+1
  if why~=M.lastRefusal or not M.lastRefusalAt or getTimestampMs()-M.lastRefusalAt>5000 then
   print('[TW-CARRY] stance key refused: '..why)
  end
  M.lastRefusal,M.lastRefusalAt=why,getTimestampMs()
  return false
 end
 return true
end
-- The server approves the stance in multiplayer; single-player stores it at once. Choosing a stance
-- also lowers the weapon from the first-person ready.
function M.setMode(player,mode)
 if not Carry.availableMode(player,mode)then return end
 M.ready[player]=nil
 if isClient()then
  M.sequence=M.sequence+1;M.selected[player]={sequence=M.sequence,mode=mode,at=getTimestampMs()}
  sendClientCommand(player,Carry.module,'SetMode',{mode=mode,weaponId=player:getPrimaryHandItem():getID(),sequence=M.sequence,session=M.session})
  M.clear(player)
 else Carry.persist(player,mode);M.clear(player)end
end
-- A native round of the own weapon in Viewpoint's first person raises it to Tarkov's ready there
-- (the user's decision B of 17.10.2026, VP-W12-H6); the stance stays. A cancelled raise, a dry trigger,
-- a shove, a remote actor or another view never reaches this path.
function M.onShot(player,weapon)
 if not player or not instanceof(player,'IsoPlayer') or not player:isLocalPlayer() or player:isDead() or player:isDoShove()
  or weapon~=player:getPrimaryHandItem() or not R.visualKey(weapon) or not weapon:isRanged() then return end
 if not (TWZB and TWZB.vpState and TWZB.vpState()==1) then return end
 local poses=rawget(_G,'TWFirstPersonPose')
 if poses and not poses.enabled then return end
 local vp=rawget(_G,'TWViewpoint')
 if not vp or not vp.enabled() or (vp.stt() and vp.giveToStt()) then return end
 if not M.isReady(player) then M.setReady(player,true,'shot') end
end
Events.OnWeaponSwingHitPoint.Add(M.onShot)
local function choose(player,mode)
 if not (player:getPrimaryHandItem() and Carry.canCarry(player)) then return end
 if M.mode(player)~=mode then M.setMode(player,mode) else M.setReady(player,false,'menu') end
end
local function crouch(player)
 player:setSneaking(not player:isSneaking())
end
local function fillStances(menu,player)
 local mode=not M.isReady(player) and M.mode(player) or nil
 menu:addSlice(H.text(getText('IGUI_TW_Radial_LowReady'),mode=='low' and getText('IGUI_TW_Radial_Current') or nil),H.texture('LowReady'),choose,player,'low')
 menu:addSlice(H.text(getText('IGUI_TW_Radial_HighReady'),mode=='high' and getText('IGUI_TW_Radial_Current') or nil),H.texture('HighReady'),choose,player,'high')
 menu:addSlice(H.text(getText('IGUI_TW_Radial_Patrol'),mode=='patrol' and getText('IGUI_TW_Radial_Current') or nil),H.texture('LowReady'),choose,player,'patrol')
 if player:isSneaking()then menu:addSlice(getText('IGUI_TW_Radial_Stand'),H.texture('Stand'),crouch,player)
 else menu:addSlice(getText('IGUI_TW_Radial_Crouch'),H.texture('Crouch'),crouch,player)end
end
H.register({id='stance',key=function()return options:getOption('ReadyToggle'):getValue()end,
 matches=function(key)return Combo.matches('ReadyToggle',key)end,
 ready=function(player,key)return keyContext(player,key)end,
 tap=function(player)
  local now=getTimestampMs();if M.lastToggle and now-M.lastToggle<300 then return end
  M.lastToggle=now
  -- From the first-person ready a tap returns to the stance; otherwise it takes the next one.
  if M.isReady(player) then M.setReady(player,false,'key') return end
  M.setMode(player,Carry.nextMode(player,M.mode(player)))
 end,
 fill=fillStances})
local function synchronize()
 if not isClient()then return end
 local player=getSpecificPlayer(0);if not player then return end
 local now=getTimestampMs()
 local selected=M.selected[player]
 -- A choice the server has not answered: ask for the state again sooner (the answer may be lost).
 local due=selected and now-selected.at>=3000 and 1000 or 3000
 if not M.lastSync or now-M.lastSync>=due then
  M.lastSync=now;sendClientCommand(player,Carry.module,'Sync',{protocolVersion=1})
  if selected and now-selected.at>=10000 then
   M.selected[player]=nil;recovered(player,'stance choice '..tostring(selected.mode)..' without a server answer')
  end
 end
end
local function guardOptionalForeignCycle(owner)
 if type(owner)~='table' or type(owner.TogglePose)~='function' then return end
 local original=owner.TogglePose
 owner.TogglePose=function(...)
  local player=getSpecificPlayer(0)
  -- With the third-person stances off the foreign key works for the mod's weapons too (outside first person).
  if player and R.visualKey(player:getPrimaryHandItem()) and M.stancesFor(player) then return end
  return original(...)
 end
 -- API delegation only: no foreign callback body, pose state or key is copied.
end
-- Gunworks toggles the underbarrel on the release of U, the carry key too: not when that release
-- (or a press) belongs to a hold menu. API delegation only, as above.
local function guardUnderbarrelKey()
 local ok,Underbarrel=pcall(require,'WeaponSystems/Utils/Underbarrel')
 if not ok or type(Underbarrel)~='table' or type(Underbarrel.ToggleUnderbarrel)~='function' or Underbarrel.twHoldGuard then return end
 local toggle=Underbarrel.ToggleUnderbarrel
 Underbarrel.twHoldGuard=true
 Underbarrel.ToggleUnderbarrel=function(...)
  if H.swallowing()then return end
  return toggle(...)
 end
end
local function attach()
 Events.OnTick.Remove(attach)
 guardUnderbarrelKey()
 Events.OnPlayerUpdate.Remove(M.update);Events.OnPlayerUpdate.Add(M.update)
 Events.OnTick.Remove(synchronize);Events.OnTick.Add(synchronize)
 guardOptionalForeignCycle(TacHold);guardOptionalForeignCycle(TacPHold)
 Events.OnEquipPrimary.Remove(M.onEquip);Events.OnEquipPrimary.Add(M.onEquip)
 if Events.OnEquipSecondary then Events.OnEquipSecondary.Remove(M.onEquip);Events.OnEquipSecondary.Add(M.onEquip) end
 -- A stance saved before FB4 is written once in the new form (an old default Low or "no stance": Patrol).
 for i=0,getNumActivePlayers()-1 do
  local player=getSpecificPlayer(i)
  if player and player:isLocalPlayer() and not isClient() then Carry.migrate(player) end
 end
 M.installed=true
end
Events.OnGameStart.Add(function()
 M.approved=setmetatable({},{__mode='k'});M.selected=setmetatable({},{__mode='k'});M.ready=setmetatable({},{__mode='k'})
 M.stale=setmetatable({},{__mode='k'});M.authorityEpoch=nil;M.lastSync=nil
 Events.OnTick.Remove(attach);Events.OnTick.Add(attach)
end)
Events.OnEquipPrimary.Add(M.onEquip)
if Events.OnEquipSecondary then Events.OnEquipSecondary.Add(M.onEquip) end
Events.OnPlayerDeath.Add(function(player)
 if not isClient()then Carry.persist(player,Carry.defaultMode)end
 M.approved[player]=nil;M.selected[player]=nil;M.ready[player]=nil;M.clear(player)
end)
return M
