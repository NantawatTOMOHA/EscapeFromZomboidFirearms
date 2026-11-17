local C=require('TW/Core')
local R=require('TW/Runtime')
local T=require('TW/Tactical')
local U=require('TW/UIHelpers')
local Sight=require('TW/SightState')
require('TW/Action')
local Window=require('TW/AssemblyWindow')
require('TW/TarkovUI/InspectWindow')
-- A weapon in a container the player can reach but does not carry (a crate, the floor, a car trunk) is taken
-- into the inventory before the change: the change must act on the copy the server owns, as vanilla's
-- weapon-upgrade menu does (ISInventoryPaneContextMenu.onUpgradeWeapon transfers, then queues the action).
local function queue(player,weapon,mode,path,part)
    if not R.inInventory(player,weapon) then ISInventoryPaneContextMenu.transferIfNeeded(player,weapon) end
    ISTimedActionQueue.add(ISTWAssemblyAction:new(player,weapon,mode,path,part))
end

local function addDevices(context,player,weapon,key,tree,parts)
    local devices=T.devices(tree,parts)
    if #devices==0 then return end
    local top=context:addOption(getText('ContextMenu_TW_Devices'))
    local menu=ISContextMenu:getNew(context)
    context:addSubMenu(top,menu)
    local anyOn=false
    for _,d in ipairs(devices) do if T.state(d.part,d.tpl).on then anyOn=true end end
    menu:addOption(getText(anyOn and 'ContextMenu_TW_DevicesOff' or 'ContextMenu_TW_DevicesOn'),player,T.toggle,weapon)
    for _,d in ipairs(devices) do
        local state=T.state(d.part,d.tpl)
        local option=menu:addOption(U.partName(d.tpl)..': '..(state.on and U.modeName(d.tpl,state.mode) or getText('ContextMenu_TW_Mode_off')))
        local sub=ISContextMenu:getNew(menu)
        menu:addSubMenu(option,sub)
        sub:addOption(getText(state.on and 'ContextMenu_Turn_Off' or 'ContextMenu_Turn_On'),player,T.set,weapon,d.part,nil,not state.on)
        for mode=2,#T.modes(d.tpl) do
            local item=sub:addOption(U.modeName(d.tpl,mode),player,T.set,weapon,d.part,mode,true)
            if state.on and state.mode==mode then item.checkMark=true end
        end
    end
end

local function addSights(context,player,weapon,tree)
    local sights=R.sights(weapon,tree,R.key(weapon))
    if #sights==0 or (#sights==1 and #sights[1].modes<2) then return end
    local active=Sight.current(player)
    local top=context:addOption(getText('ContextMenu_TW_ActiveSight'))
    local menu=ISContextMenu:getNew(context)
    context:addSubMenu(top,menu)
    for _,s in ipairs(sights) do
        for mode=1,math.max(1,#s.modes) do
            local label=Sight.name(s)
            if #s.modes>1 then label=label..' - '..Sight.modeText(s,mode) end
            local option=menu:addOption(label,player,Sight.select,weapon,s.path,s.channel,#s.modes>0 and mode or nil)
            if active and s.path==active.path and s.channel==active.channel and mode==active.mode then option.checkMark=true end
        end
    end
end

-- A loose Tarkov part opens the build planner of a weapon that accepts it.
local function addPartPlanner(context,player,items)
    for _,entry in ipairs(items) do
        local item=entry.items and entry.items[1] or entry
        local meta=R.meta(item)
        local weapons=meta and C.data.items[meta.tpl].weapons
        if weapons and weapons[1] then
            local key=weapons[1]
            -- A weapon of the player's own: the main inventory or a bag in it (a weapon in someone's crate is
            -- not the player's to build).
            local carried={}
            R.eachItem(player,function(carriedItem) local k=R.key(carriedItem); if k then carried[k]=true end end)
            local owned
            for _,k in ipairs(weapons) do if not owned and carried[k] then owned=k end end
            context:addOption(getText('ContextMenu_TW_Planner'),player,function(p,k,tpl) Window.openPlanner(p,k,tpl) end,owned or key,meta.tpl)
            return
        end
    end
end

local function fill(playerIndex,context,items)
    local player=getSpecificPlayer(playerIndex)
    local weapon
    for _,entry in ipairs(items) do
        local item=entry.items and entry.items[1] or entry
        if TWLaunchers and TWLaunchers.isUnderbarrel(item) then
            local option=context:addOption(getText('UI_TW_ReturnMainWeapon'))
            option.notAvailable=true
            return
        end
        if R.isWeapon(item) then weapon=item; break end
    end
    if not weapon then addPartPlanner(context,player,items); return end
    local key=R.key(weapon)
    -- Tarkov's item menu: INSPECT (the small window), MODDING and EDIT BUILD (the full screens).
    context:addOption(getText('ContextMenu_TW_Inspect'),player,function(p,w) TWInspectWindow.open(p,w) end,weapon)
    context:addOption(getText('ContextMenu_TW_Customize'),player,Window.open,weapon)
    context:addOption(getText('ContextMenu_TW_Planner'),player,function(p,w) Window.open(p,w,'plan') end,weapon)
    local tree,_,installed=R.view(weapon)
    if player:getPrimaryHandItem()==weapon then
        addDevices(context,player,weapon,key,tree,installed)
        addSights(context,player,weapon,tree)
    end
    if getDebug() and not isClient() then
        local debugTop=context:addOption(getText('ContextMenu_TW_DebugPreset'))
        local debugMenu=ISContextMenu:getNew(context)
        context:addSubMenu(debugTop,debugMenu)
        local names={}
        for name in pairs(C.data.weapons[key].presets) do names[#names+1]=name end
        table.sort(names)
        for _,name in ipairs(names) do
            debugMenu:addOption(name,player,function(p,w,n)
                local ok,reason=R.debugApplyPreset(p,w,n)
                if not ok then p:Say(U.reason(reason)) end
            end,weapon,name)
        end
    end
    local top=context:addOption(getText('ContextMenu_TW_Assembly'))
    local menu=ISContextMenu:getNew(context)
    context:addSubMenu(top,menu)
    local s=C.stats(key,tree)
    local state=menu:addOption(getText(s.ready and 'ContextMenu_TW_Ready' or 'ContextMenu_TW_Incomplete'))
    state.notAvailable=true
    state.toolTip=ISWorldObjectContextMenu.addToolTip()
    state.toolTip.description=getText('ContextMenu_TW_Stats',string.format('%.2f',s.weight),string.format('%.1f',s.ergonomics),string.format('%.1f',s.recoilUp))
    if not s.ready then
        local names={}
        for _,path in ipairs(s.missing) do names[#names+1]=U.slotName(path,key) end
        state.toolTip.description=state.toolTip.description..' <LINE> '..getText('ContextMenu_TW_Missing',table.concat(names,', '))
    end
    local paths={}
    for path in pairs(tree) do paths[#paths+1]=path end
    table.sort(paths)
    -- A weapon outside the inventory is brought into it by queue() before the change; the rules then check it
    -- as the weapon the player carries.
    local transfer=not R.inInventory(player,weapon)
    for _,path in ipairs(paths) do
        local part=installed[path]
        if not C.isClip(tree[path]) then
            local allowed,reason=R.canRemove(player,weapon,path,false,transfer)
            local mode='remove'
            if reason=='hasChildren' then
                mode='removeBranch'
                allowed,reason=R.canRemove(player,weapon,path,true,transfer)
            end
            local option=menu:addOption(getText(mode=='removeBranch' and 'ContextMenu_TW_RemoveBranch' or 'ContextMenu_TW_Remove',part:getName()),player,queue,weapon,mode,path,nil)
            option.notAvailable=not allowed
            if mode=='removeBranch' then
                option.toolTip=ISWorldObjectContextMenu.addToolTip()
                option.toolTip.description=getText('ContextMenu_TW_RemoveChildren')
            end
        end
    end
    -- The parts the player carries: the main inventory and the bags in it (a part in a bag is installed from
    -- there; the action takes it out of its own container).
    local parts,templates={},{}
    R.eachItem(player,function(item)
        local meta=R.meta(item)
        if meta then parts[#parts+1]=item;templates[item]=meta.tpl end
    end)
    if #parts==0 then return end
    local count=0
    for _,slot in ipairs(C.availableSlots(key,tree)) do
        if count>=40 then break end
        -- C.validate always requires this membership. Skip only impossible pairs; every candidate
        -- still goes through the runtime's inventory, reach, conflict and copy checks, in the same order.
        local allowed={}
        for _,tpl in ipairs(slot.definition.allowed) do allowed[tpl]=true end
        for _,part in ipairs(parts) do
            if count<40 and allowed[templates[part]] and R.canInstall(player,weapon,part,slot.path,transfer) then
                count=count+1
                menu:addOption(getText('ContextMenu_TW_Install',part:getName(),U.slotName(slot.path,key)),player,queue,weapon,'install',slot.path,part)
            end
        end
    end
end
Events.OnFillInventoryObjectContextMenu.Add(fill)
