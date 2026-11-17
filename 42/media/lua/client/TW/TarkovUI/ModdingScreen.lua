-- Escape from Tarkov's weapon screens in Project Zomboid (user's task of 20.10.2026, references in
-- docs/REPORT_RU.md "Меню Таркова"): one full-screen screen over the frosted game view with two
-- modes, as Tarkov's two screens:
--   live  WEAPON MODDING - the real weapon; a cell's grid offers the parts the player carries and
--         installs them by timed actions, with the progress in the middle of the screen;
--   plan  WEAPON BUILDS  - a build (draft) of any supported weapon with every compatible part,
--         saved, opened, shared as a code and assembled from the inventory by a validated plan.
-- The weapon turns by dragging; every slot has a cell on a ring around it with a leader line to
-- its mount point; INFO at the left pulls out the characteristics that act in Project Zomboid.
-- The global name TWAssemblyWindow and its methods stay for the mod's other code and the QA.
require('ISUI/ISPanel')
require('ISUI/ISTextEntryBox')
require('TW/Action')
require('TimedActions/ISEquipWeaponAction')
require('TimedActions/ISEjectMagazine')
require('TimedActions/ISInsertMagazine')
require('TimedActions/ISInventoryTransferUtil')
local C=require('TW/Core')
local R=require('TW/Runtime')
local Render=require('TW/Render')
local GeometryState=require('TW/GeometryState')
local T=require('TW/Tactical')
local U=require('TW/UIHelpers')
local Sight=require('TW/SightState')
local B=require('TW/Builds')
local Magazine=require('WeaponSystems/Utils/Magazine')
local Theme=require('TW/TarkovUI/Theme')
local Groups=require('TW/TarkovUI/Groups')
local Stats=require('TW/TarkovUI/Stats')
local Frosted=require('TW/TarkovUI/Frosted')
local GamepadBuilder=require('TW_GamepadBuilder')
require('TW/TarkovUI/Scene')
require('TW/TarkovUI/SlotLayer')
require('TW/TarkovUI/PartGrid')
require('TW/TarkovUI/StatsPanel')
require('TW/TarkovUI/Widgets')
require('TW/TarkovUI/BuildWindows')
local D=C.data

local function withoutMagazine(tree)
    local result=C.copy(tree)
    if C.isClip(result.mod_magazine) then result.mod_magazine=nil end
    return result
end

TWAssemblyWindow=ISPanel:derive('TWAssemblyWindow')
TWAssemblyWindow.instances={}

-- Match the vanilla item-list permission in MP; client debug mode alone never grants items.
function TWAssemblyWindow:canDebugSpawn()
    if not self.player or self.player:isDead() then return false end
    if not isClient() then return getDebug() end
    local role=self.player:getRole()
    return role~=nil and role:hasCapability(Capability.AddItem)
end

-- The body stands while the screen is up, as with the vanilla world map (ISWorldMap sets the same flag). A modal
-- screen taken out of the UIManager by anything else - another mod's "close all UI", a death, a state change -
-- never reaches close(), and the flag then holds the character for good ("stands still in place and stops moving",
-- W11 standstill: measured in game, 0.00 tiles in every open direction with the flag left on). The holds this
-- screen took are kept here and released by its own guard on the next tick. Only a hold this screen set is ever
-- cleared (the flag was off when it took it), so the world map's or another mod's block is left alone.
-- The hold follows the screen: hidden (the Esc menu and the hide-UI key hide every window with setVisible(false) and
-- show them again later), the guard frees the body; drawn again, prerender takes the hold back. A screen hidden for
-- good by another mod is never drawn again, so it never holds the character.
TWAssemblyWindow.blockers={}
function TWAssemblyWindow.takeBlock(window)
    local player=window and window.player
    if not player or window.blockedMovement or player:isDead() or player:isBlockMovement() then return false end
    if TWAssemblyWindow.instances[window.playerIndex]~=window then return false end
    window.blockedMovement=true
    window.pumpSeen=TWAssemblyWindow.pump
    player:setBlockMovement(true)
    TWAssemblyWindow.blockers[window.playerIndex]=window
    TWAssemblyWindow.holds=(TWAssemblyWindow.holds or 0)+1
    return true
end
function TWAssemblyWindow.releaseBlock(window)
    if not window or not window.blockedMovement then return end
    window.blockedMovement=false
    TWAssemblyWindow.blockers[window.playerIndex]=nil
    TWAssemblyWindow.holds=math.max(0,(TWAssemblyWindow.holds or 0)-1)
    local player=window.player
    if player then player:setBlockMovement(false) end
end
-- One tick: a hold whose screen is gone from the UI (no update() for a few ticks, hidden, or its player dead or
-- replaced) is released. Nothing runs while no hold exists (one counter check; this Lua has no next()); while it
-- exists the screen of this player is one entry.
TWAssemblyWindow.pump=0
TWAssemblyWindow.holds=0
local function guardBlocks()
    if TWAssemblyWindow.holds<=0 then return end
    TWAssemblyWindow.pump=TWAssemblyWindow.pump+1
    for index,window in pairs(TWAssemblyWindow.blockers) do
        local player=window.player
        local held=player~=nil and not player:isDead() and player:getPlayerNum()==index
            and window:getIsVisible()==true and (window.pumpSeen or -1)>=TWAssemblyWindow.pump-4
        if not held then TWAssemblyWindow.releaseBlock(window) end
    end
end
local function releaseBlocksOf(player)
    if TWAssemblyWindow.holds<=0 then return end
    for _,window in pairs(TWAssemblyWindow.blockers) do
        if window.player==player then TWAssemblyWindow.releaseBlock(window) end
    end
end
-- The guard is on the module table so the offline cases can drive one tick without the game's event list.
TWAssemblyWindow.guardBlocks=guardBlocks
Events.OnTick.Add(guardBlocks)
Events.OnPlayerDeath.Add(releaseBlocksOf)

-- Screen -------------------------------------------------------------------------
function TWAssemblyWindow:new(player,weapon,mode,key)
    local sw,sh=getCore():getScreenWidth(),getCore():getScreenHeight()
    local o=ISPanel.new(self,0,0,sw,sh)
    o.k=Theme.scale(sh)
    o.player=player
    o.playerIndex=player:getPlayerNum()
    o.weapon=weapon
    o.liveKey=weapon and R.key(weapon) or nil
    o.planKey=key or o.liveKey
    o.mode=weapon and (mode or 'live') or 'plan'
    o.key=o.mode=='plan' and o.planKey or o.liveKey
    o.history={}
    o.previewOwner={preview=true}
    -- Pool copy for the assembly of a chosen candidate part (TWAssemblyWindow:setPreview).
    o.candidateOwner={preview=true}
    o.filters={vital=true,functional=true,gear=true}
    o.ownedOnly=false
    o.background=false
    o.moveWithMouse=false
    o.tick=0
    return o
end

-- open(player, weapon) shows the modding screen; open(player, weapon or nil, 'plan', key,
-- focusTpl) the builds screen of a weapon type.
function TWAssemblyWindow.open(player,weapon,mode,key,focusTpl)
    if TWLaunchers and TWLaunchers.isUnderbarrel(weapon) then
        player:Say(getText('UI_TW_ReturnMainWeapon'))
        return
    end
    -- A weapon in a bag of the inventory, or in a container the player can reach (a crate on the next
    -- square, the floor, a car trunk), opens its screens (user report 25.10.2026: "Inspect" worked only for
    -- the main inventory). The modding screen changes the weapon, so a weapon outside the inventory is taken
    -- into it first, as vanilla's weapon-upgrade menu does; the builds screen only reads it.
    if weapon and (not R.isWeapon(weapon) or not R.reachable(player,weapon)) then return end
    key=key or weapon and R.key(weapon)
    if not key or not D.weapons[key] then return end
    if weapon and (mode or 'live')=='live' and not R.inInventory(player,weapon) then
        return TWAssemblyWindow.transferThenOpen(player,weapon,mode,key,focusTpl)
    end
    local old=TWAssemblyWindow.instances[player:getPlayerNum()]
    if old and old.weapon==weapon and old:getIsVisible() and (mode or 'live')==old.mode and (not key or key==old.key) then old:bringToTop(); return old end
    if old then old:close() end
    if TWInspectWindow then TWInspectWindow.closeFor(player) end
    if weapon then R.refresh(weapon,nil) end
    local window=TWAssemblyWindow:new(player,weapon,mode,key)
    window.focusTpl=focusTpl
    window:initialise()
    window:addToUIManager()
    window:setAlwaysOnTop(true)
    TWAssemblyWindow.instances[player:getPlayerNum()]=window
    -- Modal as Tarkov's: the character stands while the screen is open (as with the world map).
    window.blockedMovement=false
    TWAssemblyWindow.takeBlock(window)
    GamepadBuilder.focus(window)
    return window
end
-- The weapon's transfer into the inventory (a normal server-checked action, so multiplayer moves the item
-- once and never duplicates it), then the screen of the weapon that is now carried.
function TWAssemblyWindow.transferThenOpen(player,weapon,mode,key,focusTpl)
    local action=ISInventoryTransferUtil.newInventoryTransferAction(player,weapon,weapon:getContainer(),player:getInventory())
    action:setOnComplete(TWAssemblyWindow.open,player,weapon,mode,key,focusTpl)
    ISTimedActionQueue.add(action)
    if TWInspectWindow then TWInspectWindow.closeFor(player) end
    return action
end
function TWAssemblyWindow.openPlanner(player,key,focusTpl)
    local weapon
    R.eachItem(player,function(item) if not weapon and R.key(item)==key then weapon=item end end)
    return TWAssemblyWindow.open(player,weapon,'plan',key,focusTpl)
end
function TWAssemblyWindow:close()
    if self.scene then self.scene:endDrag() end
    if self.modal then self.modal:close() end
    if self.grid then self.grid:close(); self.grid:setVisible(false) end
    if self.nameEntry and self.nameEntry:isFocused() then self.nameEntry:unfocus() end
    self:lowerMenu()
    self:setVisible(false)
    self:removeFromUIManager()
    TWAssemblyWindow.releaseBlock(self)
    GamepadBuilder.unfocus(self)
    if TWAssemblyWindow.instances[self.playerIndex]==self then TWAssemblyWindow.instances[self.playerIndex]=nil end
end
function TWAssemblyWindow:isKeyConsumed(key) return true end
function TWAssemblyWindow:onKeyRelease(key)
    if key==Keyboard.KEY_ESCAPE then
        if self.modal then self.modal:cancel()
        elseif self.grid:getIsVisible() then self.grid:close() else self:close() end
        return true
    end
    if key==Keyboard.KEY_Z and isCtrlKeyDown() and self.mode=='plan' and not self.modal then self:undo(); return true end
end
-- A window of the builds screen (TarkovUI/BuildWindows) over the screen; one at a time.
function TWAssemblyWindow:showModal(window)
    if self.modal then self.modal:close() end
    self.grid:close()
    self:clearPreview()
    window:initialise()
    self:addChild(window)
    window:bringToTop()
    self.modal=window
    return window
end
-- The screen is always on top: UIManager moves such elements behind everything else every frame,
-- so a context menu it opens must be always on top too, and later in UIManager's list. Reset when
-- the menu is gone (TWAssemblyWindow:update) or the screen closes.
function TWAssemblyWindow:contextMenu()
    self:lowerMenu()
    local menu=ISContextMenu.get(self.playerIndex,getMouseX(),getMouseY())
    menu:addToUIManager()
    menu:setAlwaysOnTop(true)
    self.raisedMenu=menu
    return menu
end
function TWAssemblyWindow:lowerMenu()
    local menu=self.raisedMenu
    if not menu then return end
    self.raisedMenu=nil
    menu:setAlwaysOnTop(false)
end

-- Layout -------------------------------------------------------------------------
function TWAssemblyWindow:createChildren()
    local k=self.k
    local W,H=self.width,self.height
    self.scene=TWTarkovScene:new(0,0,W,H,self)
    self.scene:initialise()
    self:addChild(self.scene)
    self.scene:initialiseScene()
    self.layer=TWTarkovSlotLayer:new(0,0,W,H,self)
    self.layer:initialise()
    self:addChild(self.layer)
    -- Filters under the title (Tarkov's three; the builds screen adds "only available parts").
    local px=math.floor(36*k)
    self.checks={}
    for _,f in ipairs({{'vital','IGUI_TWK_FilterVital'},{'functional','IGUI_TWK_FilterFunctional'},{'gear','IGUI_TWK_FilterGear'},{'owned','IGUI_TWK_FilterOwned'}}) do
        local check=TWTarkovCheck:new(0,0,self,self.onFilter,getText(f[2]),px,f[1]~='owned')
        check.filter,check.basePx=f[1],px
        check:initialise()
        self:addChild(check)
        self.checks[#self.checks+1]=check
    end
    self.backButton=TWTarkovTextButton:new(0,0,self,self.close,getText('IGUI_TWK_Back'),math.floor(76*k))
    self.backButton:initialise()
    self:addChild(self.backButton)
    self.resetButton=TWTarkovTextButton:new(0,0,self,self.revertDraft,getText('IGUI_TWK_Revert'),math.floor(76*k))
    self.resetButton:initialise()
    self:addChild(self.resetButton)
    -- Builds menu (left column of the builds screen).
    local bw,bh=math.floor(300*k),math.floor(40*k)
    self.menuButtons={}
    -- Tarkov's FIND PARTS opens its flea market; PZ has none, and the missing parts are named in the
    -- notice at the bottom, so the item is left out (user's report of 21.10.2026, item 3).
    for _,b in ipairs({{'newBuild','IGUI_TWK_NewBuild','Menu_new'},{'openMenu','IGUI_TWK_Open','Menu_open'},{'saveBuild','IGUI_TWK_SaveAs','Menu_save'},
        {'weaponMenu','IGUI_TWK_SelectWeapon','Menu_weapon'},{'copyCode','IGUI_TWK_Publish','Menu_publish'},
        {'deleteBuild','IGUI_TWK_DeleteBuild','Menu_delete'}}) do
        local button=TWTarkovMenuButton:new(0,0,bw,bh,self,b[1],getText(b[2]),b[3],b[1]=='deleteBuild' and 'danger' or nil)
        button:initialise()
        self:addChild(button)
        self.menuButtons[#self.menuButtons+1]=button
        self[b[1]..'Button']=button
    end
    local icon=math.floor(96*k)
    self.saveIcon=TWTarkovIconButton:new(0,0,icon,self,self.saveBuild,getText('IGUI_TWK_Save'),'Icon_save')
    self.undoIcon=TWTarkovIconButton:new(0,0,icon,self,self.undo,getText('IGUI_TWK_Undo'),'Icon_undo')
    self.assembleIcon=TWTarkovIconButton:new(0,0,icon,self,self.buildFromInventory,getText('IGUI_TWK_Assemble'),'Icon_assemble','assemble')
    for _,b in ipairs({self.saveIcon,self.undoIcon,self.assembleIcon}) do b:initialise(); self:addChild(b) end
    local fh=getTextManager():getFontHeight(UIFont.Large)
    self.nameEntry=ISTextEntryBox:new('',0,0,math.floor(430*k),math.max(fh+math.floor(10*k),math.floor(56*k)))
    self.nameEntry.font=UIFont.Large
    self.nameEntry:initialise()
    self.nameEntry.backgroundColor={r=Theme.BLACK.r,g=Theme.BLACK.g,b=Theme.BLACK.b,a=0.55}
    self.nameEntry.borderColor={r=Theme.NAME_FIELD.r,g=Theme.NAME_FIELD.g,b=Theme.NAME_FIELD.b,a=0.9}
    self.nameEntry:setPlaceholderText(getText('IGUI_TWK_BuildName'))
    self:addChild(self.nameEntry)
    self.debugButton=TWTarkovTextButton:new(0,0,self,self.debugSpawn,getText('IGUI_TWK_DebugSpawn'),math.floor(26*k))
    self.debugButton:initialise()
    self:addChild(self.debugButton)
    self.info=TWTarkovStatsPanel:new(self)
    self.info:initialise()
    self:addChild(self.info)
    self.grid=TWTarkovPartGrid:new(self)
    self.grid:initialise()
    self:addChild(self.grid)
    self.grid:setVisible(false)
    self:setWantKeyEvents(true)
    self:enterMode(self.mode)
    self:refresh(true)
end

-- The weapon's box in the middle of the screen, between the title and the bottom controls; on a
-- narrow screen it keeps right of the builds menu and under the filters.
function TWAssemblyWindow:sceneFitArea()
    local W,H=self.width,self.height
    local m=math.floor(32*self.k)
    local x1,x2=W*0.27,W*0.73
    local c=self.column
    if c and c.x2+m>x1 then
        x1=c.x2+m
        x2=math.max(W-x1,math.min(W-m,x1+W*0.3))
    end
    return {x1=x1,y1=math.max(H*0.30,(self.ringTop or 0)+math.floor(80*self.k)),x2=x2,y2=H*0.68}
end
function TWAssemblyWindow:sceneChanged() self.sceneRevision=(self.sceneRevision or 0)+1 end
-- Boxes the slot cells keep clear of: the builds menu and the INFO tab (open, the rows cover
-- the cells, as in Tarkov).
function TWAssemblyWindow:keepOut()
    local list={}
    if self.column then list[#list+1]=self.column end
    local tab,m=self.info:tabBox(),math.floor(12*self.k)
    list[#list+1]={x1=tab.x1,y1=tab.y1-m,x2=tab.x2+m,y2=tab.y2+m}
    return list
end

function TWAssemblyWindow:layout()
    local k=self.k
    local W,H=self.width,self.height
    local plan=self.mode=='plan'
    local margin=math.floor(32*k)
    -- Builds menu: the name, the menu, the save / undo / assemble icons (the builds screen's left column).
    local mx=math.floor(88*k)
    self.nameEntry:setVisible(plan)
    self.nameEntry:setX(mx);self.nameEntry:setY(math.floor(46*k))
    local my=math.max(math.floor(140*k),self.nameEntry:getY()+self.nameEntry:getHeight()+math.floor(14*k))
    local step=math.floor(44*k)
    for i,button in ipairs(self.menuButtons) do
        button:setVisible(plan)
        local extra=button==self.deleteBuildButton and math.floor(14*k) or 0
        button:setX(mx);button:setY(my+(i-1)*step+extra)
    end
    local iy=my+#self.menuButtons*step+math.floor(36*k)
    for i,b in ipairs({self.saveIcon,self.undoIcon,self.assembleIcon}) do
        b:setVisible(plan)
        b:setX(mx+(i-1)*(b.width+math.floor(6*k)));b:setY(iy)
    end
    if self.debugButton then
        self.debugButton:setVisible(self:canDebugSpawn())
        self.debugButton:setTitle(getText(plan and 'IGUI_TWK_DebugSpawnMissing' or 'IGUI_TWK_DebugSpawn'))
        -- Debug only: under the builds menu, or in the top left corner of the modding screen.
        if plan then self.debugButton:setX(mx);self.debugButton:setY(iy+math.floor(166*k))
        else self.debugButton:setX(math.floor(30*k));self.debugButton:setY(math.floor(24*k)) end
    end
    self.column=nil
    if plan then
        local parts={self.nameEntry,self.saveIcon,self.undoIcon,self.assembleIcon,self.debugButton}
        for _,b in ipairs(self.menuButtons) do parts[#parts+1]=b end
        local c={x1=mx,y1=H,x2=mx,y2=0}
        for _,b in pairs(parts) do
            if b:getIsVisible() then
                c.y1=math.min(c.y1,b:getY());c.x2=math.max(c.x2,b:getX()+b:getWidth());c.y2=math.max(c.y2,b:getY()+b:getHeight())
            end
        end
        self.column=c
    end
    self.backButton:setX(W-self.backButton.width-math.floor(170*k))
    self.backButton:setY(H-self.backButton.height-math.floor(40*k))
    self.resetButton:setVisible(plan)
    self.resetButton:setX(W-self.resetButton.width-math.floor(110*k))
    self.resetButton:setY(math.floor(60*k))
    -- Filters in a row centred under the title (Tarkov: y 197 px at 4K). Where the screen is too
    -- narrow for one row they wrap into centred rows clear of the builds menu, and a filter wider
    -- than the room on its own takes a smaller font.
    local left=self.column and self.column.x2+margin or margin
    local right=W-margin
    local visible={}
    for _,check in ipairs(self.checks) do
        check:setVisible(check.filter~='owned' or plan)
        if check.px~=check.basePx then check.px=check.basePx; check:fitSize() end
        if check:getIsVisible() then
            if check.width>right-left then
                check.px=math.max(8,math.floor(check.basePx*(right-left)/check.width)); check:fitSize()
                while check.width>right-left and check.px>8 do check.px=check.px-1; check:fitSize() end
            end
            visible[#visible+1]=check
        end
    end
    local gap=math.floor(64*k)
    local rows,row,rowH={},{w=-gap},0
    for _,check in ipairs(visible) do
        if #row>0 and row.w+gap+check.width>right-left then rows[#rows+1]=row; row={w=-gap} end
        row[#row+1]=check
        row.w=row.w+gap+check.width
        rowH=math.max(rowH,check.height)
    end
    rows[#rows+1]=row
    local y=math.floor((plan and 216 or 196)*k-rowH/2)
    if plan then y=math.max(y,self.resetButton:getY()+self.resetButton:getHeight()+math.floor(8*k)) end
    for i,r in ipairs(rows) do
        if i>1 then y=y+rowH+math.floor(10*k) end
        local x=math.max(left,math.min(right-r.w,math.floor((W-r.w)/2)))
        for _,check in ipairs(r) do
            check:setX(x); check:setY(y+math.floor((rowH-check.height)/2))
            x=x+check.width+gap
        end
    end
    self.ringTop=y+rowH+math.floor(70*k)
    self.ringBottomInset=math.floor((plan and 190 or 150)*k)
    self.layoutMode=plan
end
-- TWTarkovTextButton has no setTitle; the debug button's caption follows the mode.
function TWTarkovTextButton:setTitle(label)
    self.label=label
    self:setWidth(math.ceil(Theme.textWidth(label,self.px)+self.px*0.2))
end

function TWAssemblyWindow:onFilter(check)
    if check.filter=='owned' then
        self.ownedOnly=check.checked
        self:refreshCandidates()
    else
        self.filters[check.filter]=check.checked
        if self.grid:getIsVisible() then self.grid:close() end
    end
end
-- A slot is shown when its group's filter is on (vital = required slots, Tarkov's blue points).
function TWAssemblyWindow:slotShown(slot)
    return self.filters[Groups.slotGroup(slot)]~=false
end
-- Over the INFO pull-out or the open grid: the weapon layer keeps no hover there.
function TWAssemblyWindow:pointerOverPanels()
    if self.modal then return true end
    local info=self.info
    if info:getIsVisible() and info:isMouseOver() and info:covers(info:getMouseX(),info:getMouseY()) then return true end
    if self.grid:getIsVisible() and self.grid:isMouseOver() then return true end
    for _,b in ipairs(self.menuButtons) do if b:getIsVisible() and b:isMouseOver() then return true end end
    return false
end

-- Modes and drafts ----------------------------------------------------------------------
function TWAssemblyWindow:enterMode(mode)
    if mode=='live' and not self.weapon then mode='plan' end
    self.mode=mode
    if mode=='plan' then
        self.planKey=self.planKey or self.liveKey
        if not self.draft then self:loadDraft() end
    end
    self.key=mode=='plan' and self.planKey or self.liveKey
    self.selectedCandidate=nil
    self:layout()
end
function TWAssemblyWindow:setMode(mode)
    if self.pending then return end
    self.grid:close()
    self:clearPreview()
    self:enterMode(mode)
    self.message=nil
    self:refresh(true)
end
-- A stored draft, else the weapon in the inventory, else the default preset.
function TWAssemblyWindow:loadDraft()
    self.history={}
    local draft=B.draft(self.player,self.planKey)
    if not draft then
        local target=self:planTarget()
        draft=target and R.tree(target) or C.presetTree(self.planKey)
    end
    self.draft=draft
end
function TWAssemblyWindow:setDraft(tree,noHistory)
    if not noHistory and self.draft and not C.equalTrees(self.draft,tree) then
        self.history[#self.history+1]=self.draft
        if #self.history>60 then table.remove(self.history,1) end
    end
    self.draft=tree
    B.setDraft(self.player,self.planKey,tree)
    self:refresh(true)
end
function TWAssemblyWindow:undo()
    local previous=table.remove(self.history)
    if not previous then return false end
    self.draft=previous
    B.setDraft(self.player,self.planKey,previous)
    self.message=nil
    self:refresh(true)
    return true
end
function TWAssemblyWindow:setPlanKey(key)
    if not D.weapons[key] then return end
    self.grid:close()
    self:clearPreview()
    self.planKey=key
    self.key=key
    self.draft=nil
    self:loadDraft()
    self.selectedPath=nil
    self.nameEntry:setText('')
    self.message=nil
    self:refresh(true)
end
-- REVERT: back to the weapon's own assembly (else the default preset).
function TWAssemblyWindow:revertDraft()
    if self.mode~='plan' then return false end
    local target=self:planTarget()
    self.message=nil
    self:setDraft(target and R.tree(target) or C.presetTree(self.planKey))
    return true
end
function TWAssemblyWindow:newBuild()
    self.nameEntry:setText('')
    self.message=nil
    self:setDraft(C.presetTree(self.planKey))
    return true
end
-- Bases a build can start from: the weapon's assembly, Tarkov's presets, an empty receiver and
-- the saved builds (the old window's base list; OPEN ... lists them for every weapon).
function TWAssemblyWindow:baseOptions()
    local list={}
    if self:planTarget() then list[#list+1]={label=getText('IGUI_TWK_BaseCurrent'),data={kind='current'}} end
    local names={}
    for name in pairs(D.weapons[self.planKey].presets) do names[#names+1]=name end
    table.sort(names)
    for _,name in ipairs(names) do list[#list+1]={label=getText('IGUI_TWK_BasePreset',name),data={kind='preset',name=name}} end
    list[#list+1]={label=getText('IGUI_TWK_BaseEmpty'),data={kind='empty'}}
    for _,build in ipairs(B.list(self.player,self.planKey)) do list[#list+1]={label=getText('IGUI_TWK_BaseSaved',build.name),data={kind='saved',name=build.name}} end
    return list
end
function TWAssemblyWindow:loadBase(data)
    if not data or data.kind=='none' then return false end
    local tree
    if data.kind=='current' then
        local target=self:planTarget()
        tree=target and R.tree(target)
    elseif data.kind=='preset' then tree=C.presetTree(self.planKey,data.name)
    elseif data.kind=='empty' then tree={}
    elseif data.kind=='saved' then
        local build=B.find(self.player,self.planKey,data.name)
        tree=build and build.tree
        if build then self.nameEntry:setText(build.name) end
    end
    if not tree then return false end
    self.message=nil
    self:setDraft(tree)
    return true
end
-- OPEN ...: the saved builds and presets of every weapon, and a build code (TarkovUI/BuildWindows).
function TWAssemblyWindow:openMenu()
    return self:showModal(TWTarkovOpenWindow:new(self))
end
-- SELECT WEAPON: any weapon of the mod on the builds screen.
function TWAssemblyWindow:weaponMenu()
    return self:showModal(TWTarkovWeaponWindow:new(self))
end
function TWAssemblyWindow:saveBuild()
    local name=B.cleanName(self.nameEntry:getText())
    local ok,result=B.save(self.player,self.planKey,name,self.draft)
    self.message=ok and getText('IGUI_TW_Msg_'..result,name) or U.reason(result)
    self.messageOk=ok
    if ok then self.nameEntry:setText(name) end
    return ok
end
function TWAssemblyWindow:deleteBuild()
    local name=B.cleanName(self.nameEntry:getText())
    local ok,reason=B.delete(self.player,self.planKey,name)
    self.message=ok and getText('IGUI_TW_Msg_buildDeleted',name) or U.reason(reason)
    self.messageOk=ok
    return ok
end
-- PUBLISH ...: the build's code to the clipboard, shown in its window (TarkovUI/BuildWindows).
function TWAssemblyWindow:copyCode()
    local name=B.cleanName(self.nameEntry:getText())
    local code=B.encode(self.planKey,self.draft,name)
    Clipboard.setClipboard(code)
    self.message=getText('IGUI_TW_Msg_codeCopied')
    self.messageOk=true
    self:showModal(TWTarkovCodeWindow:new(self,code,name))
    return true
end
-- As a menu handler the first argument is the screen, not a code.
function TWAssemblyWindow:pasteCode(code)
    if type(code)~='string' then code=Clipboard.getClipboard() end
    local key,tree,dropped,name=B.decode(code)
    if not key then self.message=U.reason(tree); self.messageOk=false; return false end
    if key~=self.planKey then self:setPlanKey(key) end
    self:setDraft(tree)
    if name and name~='' then self.nameEntry:setText(name) end
    self.message=dropped>0 and getText('IGUI_TW_Msg_codeDropped',dropped) or getText('IGUI_TW_Msg_codePasted')
    self.messageOk=dropped==0
    return true
end

-- State -------------------------------------------------------------------------------
-- The weapon a build is compared with and assembled on: the screen's own weapon while the player carries it
-- (main inventory or a bag), else another weapon of the type the player carries.
function TWAssemblyWindow:planTarget()
    if self.weapon and self.liveKey==self.planKey and R.inInventory(self.player,self.weapon) then return self.weapon end
    local found
    R.eachItem(self.player,function(item) if not found and R.key(item)==self.planKey then found=item end end)
    return found
end
function TWAssemblyWindow:fingerprint()
    local values={self.mode,tostring(self.key)}
    if self.weapon then
        values[#values+1]=tostring(self.weapon:getCurrentAmmoCount())..':'..tostring(self.weapon:isContainsClip())..':'..R.fingerprint(self.weapon)
            ..':'..tostring(self.weapon:getCondition())
    end
    if self.mode=='plan' then
        values[#values+1]='p:'..B.encode(self.planKey,self.draft)
        local target=self:planTarget()
        values[#values+1]='t:'..(target and tostring(target:getID())..':'..R.fingerprint(target)..':'..tostring(target:isContainsClip()) or '-')
    end
    local main,nested=R.looseParts(self.player)
    for tpl,list in pairs(main) do values[#values+1]='i:'..tpl..':'..#list..':'..list[1]:getID() end
    for tpl,list in pairs(nested) do values[#values+1]='n:'..tpl..':'..#list end
    -- Magazines count wherever the player carries them (the main inventory or a bag).
    local clips=0
    R.eachItem(self.player,function(item)
        local meta=R.meta(item)
        if meta and C.isClip(meta.tpl) then clips=clips+1 end
    end)
    values[#values+1]='c:'..clips
    table.sort(values)
    return table.concat(values,'|')
end
function TWAssemblyWindow:refresh(force)
    local fingerprint=self:fingerprint()
    if not force and fingerprint==self.lastFingerprint then return end
    self.lastFingerprint=fingerprint
    self.revision=(self.revision or 0)+1
    -- The rows are rebuilt: the preview is taken again from the new row below.
    local previewing=self.preview~=nil
    self.preview=nil
    if self.weapon then
        self.liveTree,self.liveEntries,self.liveParts=R.read(self.weapon)
    else
        self.liveTree,self.liveEntries,self.liveParts={},{},{}
    end
    self.key=self.mode=='plan' and self.planKey or self.liveKey
    if self.mode=='plan' then
        self.tree,self.entries,self.parts=self.draft,C.entries(self.draft,self.key),{}
    else
        self.tree,self.entries,self.parts=self.liveTree,self.liveEntries,self.liveParts
    end
    self.stats=C.stats(self.key,self.tree)
    self.pz=C.pzStats(self.key,self.tree,self.stats)
    self.loose,self.looseNested=R.looseParts(self.player)
    self.slots=C.slotTree(self.key,self.tree)
    if self.mode=='plan' then self:updatePlan() else self.plan,self.target=nil,nil end
    self:markSlots()
    self:updateScene()
    local selected
    for _,slot in ipairs(self.slots) do if slot.path==self.selectedPath then selected=slot end end
    local focus
    if not selected and self.focusTpl then
        selected=self:focusSlot(self.focusTpl)
        focus=selected
        self.focusTpl=nil
    end
    if not selected then
        for _,slot in ipairs(self.slots) do if slot.path=='mod_pistol_grip' then selected=slot end end
        selected=selected or self.slots[1]
    end
    self.selectedSlot=selected
    self.selectedPath=selected and selected.path or nil
    self:refreshCandidates()
    if previewing then self.previewAt=0; self:applyPreview(math.huge) end
    self.statsCache=nil
    if focus then self.focusGrid=focus.path end
end
function TWAssemblyWindow:updateScene()
    local modelName
    local tree,entries=self.tree,self.entries
    local preview=self.preview
    if preview then
        -- A chosen candidate: its own pool copy; the weapon and the draft copy stay untouched.
        modelName=Render.preview(self.candidateOwner,self.key,preview.tree,preview.entries)
        tree,entries=preview.tree,preview.entries
    elseif self.mode=='plan' then
        modelName=Render.preview(self.previewOwner,self.key,self.tree,self.entries)
    else
        if not Render.current(self.weapon) then R.applyRender(self.weapon) end
        self.liveTree,self.liveEntries,self.liveParts=R.read(self.weapon)
        self.entries=self.liveEntries
        entries=self.entries
        modelName=self.weapon:getWeaponSprite()
    end
    if not modelName then return end
    GeometryState.annotate(self.key,tree,entries)
    local signature=self.mode..'|'..modelName..'|'..Render.fingerprint(self.key,entries)
    if signature~=self.sceneSignature then
        self.sceneSignature=signature
        -- Entering, changing and leaving a preview keep the view where it is.
        self.scene:setAssembly(self.key,tree,entries,modelName,preview~=nil or self.previewShown)
        self.previewShown=preview~=nil
    end
end

-- Preview of a hovered part: the scene and the cells show the assembly with the part while the
-- pointer rests on its cell; parts the rules refuse leave the real assembly. A part only missing
-- from the inventory is still previewed.
local PREVIEW_DELAY=70
local function previewable(row)
    return row~=nil and row.nextTree~=nil and not row.installed and (row.allowed or row.reason=='notInInventory')
end
TWAssemblyWindow.previewable=previewable
function TWAssemblyWindow:setPreview(row)
    if not previewable(row) then self:clearPreview(); return end
    if self.previewKey==row.key and self.previewPath==self.selectedPath then return end
    self.previewKey,self.previewPath=row.key,self.selectedPath
    self.previewAt=getTimestampMs()
end
function TWAssemblyWindow:clearPreview()
    if not self.previewKey and not self.preview then return end
    self.previewKey,self.previewPath=nil,nil
    self.previewAt=getTimestampMs()
end
-- The grid closed: back to the real assembly, unless the chosen part is being installed now.
function TWAssemblyWindow:endPreview()
    if self.pending and self.preview then self.holdPreview=true; return end
    self:clearPreview()
end
function TWAssemblyWindow:applyPreview(now)
    local wanted=self.previewKey and self.previewPath==self.selectedPath and self.previewKey or nil
    if wanted==(self.preview and self.preview.key) then return end
    if (now or getTimestampMs())-(self.previewAt or 0)<PREVIEW_DELAY then return end
    local row
    for _,r in ipairs(wanted and self.candidates or {}) do if r.key==wanted then row=r end end
    if previewable(row) then
        local real={}
        for _,slot in ipairs(self.slots or {}) do real[slot.path]=slot end
        local slots=C.slotTree(self.key,row.nextTree)
        -- New slots of the previewed part cannot be opened before it is installed.
        for _,slot in ipairs(slots) do
            slot.preview=real[slot.path]==nil
            if real[slot.path] then slot.mark=real[slot.path].mark end
        end
        self.preview={key=row.key,tpl=row.tpl,path=self.selectedPath,tree=row.nextTree,entries=C.entries(row.nextTree,self.key),slots=slots}
    else
        self.preview,self.previewKey,self.previewPath=nil,nil,nil
    end
    self.statsCache=nil
    self:updateScene()
end
function TWAssemblyWindow:viewTree() return self.preview and self.preview.tree or self.tree end
function TWAssemblyWindow:viewSlots() return self.preview and self.preview.slots or self.slots end
function TWAssemblyWindow:pickCandidate(row)
    self:selectCandidate(row)
    self:setPreview(row)
end
-- An explicit choice edits the build immediately; hover stays a preview.
function TWAssemblyWindow:chooseCandidate(row)
    self:pickCandidate(row)
    if self.mode=='plan' and row and row.allowed then return self:draftApply() end
    return false
end
function TWAssemblyWindow:previewCandidate(row)
    self:pickCandidate(row)
end
function TWAssemblyWindow:ownedCounts()
    local owned={}
    for tpl,list in pairs(self.loose) do owned[tpl]=#list end
    for tpl,list in pairs(self.looseNested) do owned[tpl]=(owned[tpl] or 0)+#list end
    return owned
end
function TWAssemblyWindow:updatePlan()
    self.target=self:planTarget()
    local current=self.target and R.tree(self.target) or {}
    self.plan=C.plan(self.planKey,current,self.draft,self:ownedCounts())
    self.plan.current=current
    self.plan.same=self.target~=nil and C.equalTrees(withoutMagazine(current),withoutMagazine(self.draft))
    self.planStats=self.target and C.stats(self.planKey,current) or nil
end
-- Builds marks: kept (on the weapon), owned (in the main inventory), bag (only in a bag), missing.
function TWAssemblyWindow:markSlots()
    if self.mode~='plan' or not self.plan then return end
    local missing={}
    for _,m in ipairs(self.plan.missing) do missing[m.tpl]=true end
    for _,slot in ipairs(self.slots) do
        if slot.tpl then
            if self.plan.current[slot.path]==slot.tpl then slot.mark='kept'
            elseif missing[slot.tpl] then slot.mark='missing'
            elseif not self.loose[slot.tpl] and self.looseNested[slot.tpl] then slot.mark='bag'
            else slot.mark='owned' end
        end
    end
end
-- Parts that can carry the target directly or through further parts.
local function carriers(target)
    local holds={}
    local changed=true
    while changed do
        changed=false
        for tpl,item in pairs(D.items) do
            if not holds[tpl] then
                for _,slot in ipairs(item.slots) do
                    for _,child in ipairs(slot.allowed) do
                        if child==target or holds[child] then holds[tpl]=true; changed=true; break end
                    end
                    if holds[tpl] then break end
                end
            end
        end
    end
    return holds
end
-- Slot for a part opened from its context menu: a slot that takes it, else a slot whose
-- candidates can carry it (a mount), with that carrier selected.
function TWAssemblyWindow:focusSlot(tpl)
    for _,slot in ipairs(self.slots) do
        for _,t in ipairs(slot.definition.allowed) do
            if t==tpl then self.selectedCandidate=tpl; return slot end
        end
    end
    local holds=carriers(tpl)
    local best,bestScore,bestCarrier
    for _,slot in ipairs(self.slots) do
        for _,t in ipairs(slot.definition.allowed) do
            if holds[t] and t~=slot.tpl then
                local direct=false
                for _,s in ipairs(D.items[t].slots) do
                    for _,child in ipairs(s.allowed) do if child==tpl then direct=true end end
                end
                local score=(slot.tpl and 0 or 1000)+slot.depth*10+(direct and 1 or 0)
                if not bestScore or score>bestScore then best,bestScore,bestCarrier=slot,score,t end
            end
        end
    end
    if best then
        self.selectedCandidate=bestCarrier
        self.message=getText('IGUI_TW_Msg_mountFirst',U.shortName(tpl))
        self.messageOk=true
    end
    return best
end
function TWAssemblyWindow:selectSlot(path)
    if path~=self.selectedPath then self:clearPreview() end
    self.selectedPath=path
    for _,slot in ipairs(self.slots) do if slot.path==path then self.selectedSlot=slot end end
    self.selectedCandidate=nil
    self:refreshCandidates()
end
-- A cell (or its mount point) opens the slot's grid next to it.
function TWAssemblyWindow:openGrid(cell)
    if cell.slot and cell.slot.preview then return false end
    self:selectSlot(cell.key)
    self.grid:open(cell.key,cell)
    return true
end
-- Rounds of the inserted magazine (modding screen): "26/30" as Tarkov's magazine cell.
function TWAssemblyWindow:roundsText(slot)
    if self.mode~='live' or slot.path~='mod_magazine' or not slot.tpl or not C.isClip(slot.tpl) or not self.weapon:isContainsClip() then return nil end
    return tostring(self.weapon:getCurrentAmmoCount())..'/'..tostring(self.weapon:getMaxAmmo())
end

-- Candidates ---------------------------------------------------------------------------
function TWAssemblyWindow:candidateRow(tpl)
    local path=self.selectedPath
    local current=self.tree[path]
    local main=self.loose[tpl] or {}
    local nested=self.looseNested[tpl] or {}
    local row={key=tpl,tpl=tpl,main=#main,nested=#nested,installed=current==tpl}
    local isMagazine=C.isClip(tpl)
    if self.mode=='plan' then
        if current==tpl then row.reason='alreadyInstalled'
        elseif current then row.mode='replace'; row.nextTree,row.reason,row.detail,row.dropped=C.swap(self.key,self.tree,path,tpl)
        else row.mode='install'; row.nextTree,row.reason,row.detail=C.install(self.key,self.tree,path,tpl) end
        row.allowed=row.nextTree~=nil
        if isMagazine and self.target then
            -- The magazine is changed by reloading: owned magazines of this weapon count, carried anywhere.
            R.eachItem(self.player,function(item) if item:getFullType()==C.clipType(self.key,tpl) then row.main=row.main+1 end end)
        end
        return row
    end
    if isMagazine then
        -- Gunworks inserts a magazine item; prefer the fullest one of this type, carried anywhere.
        local best
        row.main=0
        R.eachItem(self.player,function(item)
            if item:getFullType()==C.clipType(self.key,tpl) then
                row.main=row.main+1
                if not best or item:getCurrentAmmoCount()>best:getCurrentAmmoCount() then best=item end
            end
        end)
        row.mode='magazine'
        row.part=best
        row.nextTree=C.copy(self.tree)
        row.nextTree.mod_magazine=tpl
        local inProfile=best and Magazine.IsMagazineInProfile(best:getFullType(),Magazine.GetProfileForGun(self.weapon))
        row.allowed=best~=nil and self.stats.ready and inProfile or false
        row.reason=not best and 'notInInventory' or not self.stats.ready and 'incompleteWeapon' or not inProfile and 'magazineConflict' or nil
        row.installed=false
        return row
    end
    local reason,detail
    row.mode=current and 'replace' or 'install'
    if current then row.nextTree,reason,detail=C.replace(self.key,self.tree,path,tpl)
    else row.nextTree,reason,detail=C.install(self.key,self.tree,path,tpl) end
    -- A module that carries parts: swap it through a plan; the parts that fit the new module are
    -- installed on it again, the others go to the inventory.
    if current and current~=tpl and reason=='hasChildren' then
        local swapped,swapReason,swapDetail,dropped=C.swap(self.key,self.tree,path,tpl)
        if swapped then
            row.mode,row.nextTree,row.dropped,reason,detail='swap',swapped,dropped,nil,nil
        else
            reason,detail=swapReason,swapDetail
        end
    end
    row.detail=detail
    if current==tpl then
        row.allowed,row.reason=false,'alreadyInstalled'
    elseif row.mode=='swap' and (main[1] or nested[1]) then
        row.part,row.fromBag=main[1] or nested[1],main[1]==nil
        row.allowed=true
    elseif main[1] then
        row.part=main[1]
        if current then row.allowed,row.reason=R.canReplace(self.player,self.weapon,main[1],path)
        else row.allowed,row.reason=R.canInstall(self.player,self.weapon,main[1],path) end
    elseif nested[1] then
        -- In a bag: moved to the main inventory first, then installed.
        row.part,row.fromBag=nested[1],true
        row.allowed=row.nextTree~=nil
        row.reason=reason
    else
        row.allowed,row.reason=false,reason or 'notInInventory'
    end
    row.allowed=row.allowed==true
    return row
end
-- Modding offers the parts the player carries (and the fitted one); builds offer every compatible
-- part, or only the available ones with "Use only available parts".
function TWAssemblyWindow:refreshCandidates()
    if not self.selectedSlot then
        self.candidates={}
        self:selectCandidate(nil)
        return
    end
    local previous=self.selectedCandidate
    local rows,selectedRow={},nil
    for _,tpl in ipairs(self.selectedSlot.definition.allowed) do
        local row=self:candidateRow(tpl)
        local owned=row.main>0 or row.nested>0
        local keep=row.installed or owned or self.mode=='plan' and not self.ownedOnly
        if keep then
            rows[#rows+1]=row
            if row.key==previous then selectedRow=row end
        end
    end
    table.sort(rows,function(a,b)
        if a.installed~=b.installed then return a.installed end
        local oa,ob=a.main+a.nested>0,b.main+b.nested>0
        if oa~=ob then return oa end
        if a.allowed~=b.allowed then return a.allowed end
        local na,nb=U.shortName(a.tpl),U.shortName(b.tpl)
        if na~=nb then return na<nb end
        return a.tpl<b.tpl
    end)
    self.candidates=rows
    self:selectCandidate(selectedRow)
end
function TWAssemblyWindow:selectCandidate(row)
    self.candidate=row
    self.selectedCandidate=row and row.key or nil
    self:updateState()
end
function TWAssemblyWindow:selectedDevice(path)
    path=path or self.selectedPath
    local tpl=self.mode=='live' and self.tree and self.tree[path]
    if tpl and T.isDevice(tpl) and self.parts[path] then return self.parts[path],tpl end
end

-- State of the controls ------------------------------------------------------------------
function TWAssemblyWindow:canBuild()
    if self.pending then return false,'busy' end
    local plan=self.plan
    if not self.target or not plan then return false,'noTarget' end
    if plan.same then return false,'planSame' end
    if not self.stats.ready then return false,'planIncomplete' end
    if #plan.missing>0 then return false,'missingParts' end
    if plan.error then return false,plan.error end
    if plan.changes==0 then return false,'planSame' end
    return true
end
-- Whether the selected slot's part can come off now (the grid's EMPTY cell, middle click).
function TWAssemblyWindow:updateState()
    if self.debugButton and self.debugButton:getIsVisible()~=self:canDebugSpawn() then self:layout() end
    if not self.layer then return end
    if self.mode=='plan' then
        self.removeAllowed=self.tree[self.selectedPath]~=nil
        self.removeReason=nil
        self.removeBranch=self.removeAllowed and #C.children(self.tree,self.selectedPath)>0
        local ok=self:canBuild()
        self.assembleIcon.ready=ok
        self.assembleIcon.enabled=not self.pending
        self.undoIcon.enabled=#self.history>0
        self.deleteBuildButton.enabled=B.find(self.player,self.planKey,B.cleanName(self.nameEntry:getText()))~=nil
        self.saveIcon.enabled=true
        return
    end
    local allowed,reason=R.canRemove(self.player,self.weapon,self.selectedPath)
    self.removeBranch=false
    if reason=='hasChildren' then
        local branchAllowed,branchReason=R.canRemove(self.player,self.weapon,self.selectedPath,true)
        self.removeBranch=branchAllowed
        allowed,reason=branchAllowed,branchReason
    end
    if self:clipSelected() then
        allowed=self.weapon:isContainsClip() and self.stats.ready
        reason=not self.stats.ready and 'incompleteWeapon' or not self.weapon:isContainsClip() and 'empty' or nil
    end
    self.removeReason=reason
    self.removeAllowed=not self.pending and allowed or false
end
-- Kept for callers of the old window.
TWAssemblyWindow.updateButtons=TWAssemblyWindow.updateState

-- Live actions ---------------------------------------------------------------------------
function TWAssemblyWindow:toggleDevice(path)
    local device,tpl=self:selectedDevice(path)
    if not device then return end
    T.set(self.player,self.weapon,device,nil,not T.state(device,tpl).on)
    self:refresh(true)
end
function TWAssemblyWindow:setDeviceMode(path,mode)
    local device=self:selectedDevice(path)
    if not device then return end
    T.set(self.player,self.weapon,device,mode,true)
    self:refresh(true)
end
function TWAssemblyWindow:sightChoices(path)
    path=path or self.selectedPath
    local choices={}
    for _,s in ipairs(C.sights(self.tree or {},self.key)) do
        if s.path==path then
            for mode=1,math.max(1,#s.modes) do choices[#choices+1]={channel=s,mode=#s.modes>0 and mode or nil} end
        end
    end
    return choices
end
function TWAssemblyWindow:makeActiveSight(path)
    local choices=self:sightChoices(path)
    local function choose(choice)
        Sight.select(self.player,self.weapon,choice.channel.path,choice.channel.channel,choice.mode)
        self:refresh(true)
    end
    if #choices==1 then choose(choices[1])
    elseif #choices>1 then
        local menu=self:contextMenu()
        for _,choice in ipairs(choices) do
            local label=Sight.name(choice.channel)
            if #choice.channel.modes>1 then label=label..' - '..Sight.modeText(choice.channel,choice.mode) end
            menu:addOption(label,choice,choose)
        end
    end
end
-- Right click on a cell, a mount point or the part itself: Tarkov's item menu of the slot.
function TWAssemblyWindow:cellMenu(cell)
    local path=cell.key
    self:selectSlot(path)
    self:updateState()
    local menu=self:contextMenu()
    local slot=cell.slot
    if slot.tpl then
        local remove=menu:addOption(getText(self.removeBranch and 'IGUI_TWK_UninstallBranch' or 'IGUI_TWK_Uninstall'),self,TWAssemblyWindow.removeSelected)
        remove.notAvailable=not self.removeAllowed
        if not self.removeAllowed and self.removeReason then
            remove.toolTip=ISWorldObjectContextMenu.addToolTip()
            remove.toolTip.description=U.reason(self.removeReason)
        end
    end
    if self.mode=='live' then
        local device,tpl=self:selectedDevice(path)
        if device then
            local state=T.state(device,tpl)
            menu:addOption(getText(state.on and 'ContextMenu_Turn_Off' or 'ContextMenu_Turn_On'),self,TWAssemblyWindow.toggleDevice,path)
            for mode=2,#T.modes(tpl) do
                local option=menu:addOption(U.modeName(tpl,mode),self,TWAssemblyWindow.setDeviceMode,path,mode)
                if state.on and state.mode==mode then option.checkMark=true end
            end
        end
        if #self:sightChoices(path)>0 then menu:addOption(getText('IGUI_TW_MakeActiveSight'),self,TWAssemblyWindow.makeActiveSight,path) end
    end
    return menu
end
function TWAssemblyWindow:queue(mode,part,fromBag)
    if self.pending then return end
    local allowed,reason
    if fromBag then
        allowed=self.candidate and self.candidate.allowed
        reason=self.candidate and self.candidate.reason
    elseif mode=='remove' or mode=='removeBranch' then allowed,reason=R.canRemove(self.player,self.weapon,self.selectedPath,mode=='removeBranch')
    elseif mode=='replace' then allowed,reason=R.canReplace(self.player,self.weapon,part,self.selectedPath)
    else allowed,reason=R.canInstall(self.player,self.weapon,part,self.selectedPath) end
    if not allowed then self.message=U.reason(reason); self.messageOk=false; self:refresh(true); return false end
    if fromBag then ISInventoryPaneContextMenu.transferIfNeeded(self.player,part) end
    self.pending=ISTWAssemblyAction:new(self.player,self.weapon,mode,self.selectedPath,part)
    ISTimedActionQueue.add(self.pending)
    self:startProgress(1,mode,part and R.meta(part) and R.meta(part).tpl or self.tree[self.selectedPath])
    self.message=nil
    self:updateState()
    return true
end
function TWAssemblyWindow:applyCandidate()
    local row=self.candidate
    if self.mode=='plan' then return self:draftApply() end
    if row and row.allowed and row.mode=='magazine' then return self:queueMagazine(row.part) end
    if row and row.allowed and row.mode=='swap' then return self:queueSwap(row) end
    if row and row.allowed then return self:queue(row.mode,row.part,row.fromBag) end
    return false
end
function TWAssemblyWindow:queueMagazine(part)
    if self.pending or not self.stats.ready or not R.inInventory(self.player,self.weapon) then return false end
    if part and (not R.inInventory(self.player,part) or not Magazine.IsMagazineInProfile(part:getFullType(),Magazine.GetProfileForGun(self.weapon))) then
        self:refresh(true); return false
    end
    if not part and not self.weapon:isContainsClip() then return false end
    local actions={}
    if self.player:getPrimaryHandItem()~=self.weapon then actions[#actions+1]=ISEquipWeaponAction:new(self.player,self.weapon,25,true,self.weapon:isTwoHandWeapon()) end
    -- ISInsertMagazine wants the magazine in the main inventory: one carried in a bag comes out first.
    if part and not self.player:getInventory():contains(part) then ISInventoryPaneContextMenu.transferIfNeeded(self.player,part) end
    if self.weapon:isContainsClip() then actions[#actions+1]=ISEjectMagazine:new(self.player,self.weapon) end
    if part then actions[#actions+1]=ISInsertMagazine:new(self.player,self.weapon,part) end
    self.pending=actions[#actions]
    for _,action in ipairs(actions) do ISTimedActionQueue.add(action) end
    self:startProgress(#actions,part and 'install' or 'remove',self.tree.mod_magazine or (part and R.meta(part) and R.meta(part).tpl))
    self:updateState()
    return true
end
-- Grid choice: EMPTY takes the part off, a part goes in (modding) or into the build (builds).
function TWAssemblyWindow:chooseFromGrid(entry)
    if entry.remove then
        self:updateState()
        if self.removeAllowed and self:removeSelected() then self.grid:close() end
        if not self.removeAllowed and self.removeReason then self.message=U.reason(self.removeReason); self.messageOk=false end
        return
    end
    self:previewCandidate(entry)
    if entry.allowed and self:applyCandidate() then self.grid:close(); return end
    if not entry.allowed then self.message=U.reasonText(entry.reason,entry.detail,entry.tpl); self.messageOk=false end
end
TWAssemblyWindow.chooseFromPopup=TWAssemblyWindow.chooseFromGrid
-- Middle mouse button on a cell, a mount point or the part itself: the part comes off (the
-- Tarkov habit). Slots of a previewed part do not exist yet; the open grid covers what is below.
function TWAssemblyWindow:slotUnderMouse()
    if self.grid:getIsVisible() and self.grid:isMouseOver() then return end
    local o=self.layer
    if not o:isMouseOver() then return end
    local x,y=o:getMouseX(),o:getMouseY()
    local cell=o:targetAt(x,y)
    if cell then return not cell.slot.preview and cell.slot.path or nil end
    local part=o:partAt(x,y)
    return part and part.slot.path or nil
end
function TWAssemblyWindow:removeUnderMouse()
    local path=self:slotUnderMouse()
    if not path then return false end
    if self.grid:getIsVisible() then self.grid:close() end
    self:selectSlot(path)
    self:updateState()
    if not self.removeAllowed then
        if self.mode~='plan' and self.removeReason then self.message=U.reason(self.removeReason); self.messageOk=false end
        return false
    end
    return self:removeSelected()~=false
end
-- B42 hands the middle mouse button to Lua as key 10002 on the frame of the press.
TWAssemblyWindow.MIDDLE_KEY=10002
function TWAssemblyWindow:middleClick()
    self.middleAt=getTimestampMs()
    local path=self:slotUnderMouse()
    local removed=self:removeUnderMouse()
    print('[TW-ASSEMBLY] middle click '..self.mode..' slot='..tostring(path)..' removed='..tostring(removed))
    return removed
end
function TWAssemblyWindow.onKeyStartPressed(key)
    if key~=TWAssemblyWindow.MIDDLE_KEY then return end
    for _,window in pairs(TWAssemblyWindow.instances) do
        if window:getIsVisible() then window:middleClick() end
    end
end
Events.OnKeyStartPressed.Add(TWAssemblyWindow.onKeyStartPressed)
-- The selected slot holds the inserted magazine (Gunworks Clip), not a built-in one.
function TWAssemblyWindow:clipSelected()
    local installed=self.tree and self.tree.mod_magazine
    return self.selectedPath=='mod_magazine' and (installed==nil or C.isClip(installed))
end
function TWAssemblyWindow:removeSelected()
    if self.mode=='plan' then return self:draftRemove() end
    if self:clipSelected() then return self:queueMagazine(nil) end
    return self:queue(self.removeBranch and 'removeBranch' or 'remove',nil)
end

-- Builds actions --------------------------------------------------------------------------
function TWAssemblyWindow:draftApply()
    local row=self.candidate
    if not row or not row.allowed or not row.nextTree then return false end
    local dropped=row.dropped and #row.dropped or 0
    self:setDraft(row.nextTree)
    self.message=dropped>0 and getText('IGUI_TW_Msg_partsDropped',dropped) or nil
    self.messageOk=dropped==0
    return true
end
function TWAssemblyWindow:draftRemove()
    if not self.draft[self.selectedPath] then return false end
    local nextTree=C.removeBranch(self.planKey,self.draft,self.selectedPath)
    self.message=nil
    self:setDraft(nextTree)
    return true
end
-- Parts freed by the plan's removal steps (whole branches come back).
local function freedParts(plan)
    local freed={}
    for _,step in ipairs(plan.steps) do
        if step.mode~='install' then
            local paths={step.path}
            if step.mode=='removeBranch' then paths=C.children(plan.current,step.path); paths[#paths+1]=step.path end
            for _,path in ipairs(paths) do
                local tpl=plan.current[path]
                if tpl then freed[tpl]=(freed[tpl] or 0)+1 end
            end
        end
    end
    return freed
end
function TWAssemblyWindow:buildFromInventory()
    local ok,reason=self:canBuild()
    if not ok then self.message=U.reason(reason); self.messageOk=false; return false end
    return self:queuePlan(self.target,self.plan)
end
-- Modding: replace a module that carries parts.
function TWAssemblyWindow:queueSwap(row)
    if self.pending then return false end
    local plan=C.plan(self.key,self.liveTree,row.nextTree,self:ownedCounts())
    plan.current=self.liveTree
    if plan.error or #plan.missing>0 then self.message=U.reasonText(plan.error or 'missingParts',plan.errorDetail,row.tpl); self.messageOk=false; return false end
    return self:queuePlan(self.weapon,plan,row.tpl)
end
-- Queue the validated steps of a plan; parts are picked when each step starts.
function TWAssemblyWindow:queuePlan(weapon,plan,tpl)
    local player=self.player
    -- Parts that are only in bags are moved to the main inventory first.
    local freed=freedParts(plan)
    local count=0
    for need,n in pairs(plan.need) do
        local fromBags=n-#(self.loose[need] or {})-(freed[need] or 0)
        for i=1,math.min(fromBags,#(self.looseNested[need] or {})) do
            ISInventoryPaneContextMenu.transferIfNeeded(player,self.looseNested[need][i])
            count=count+1
        end
    end
    local last
    -- A step that takes off a vital part makes the weapon unready and Runtime.refresh takes it out of
    -- the hands; a weapon held when the build started goes back to them at the end (21.10.2026 MP run:
    -- after a build the weapon was left in the inventory).
    local held=player:getPrimaryHandItem()==weapon
    if plan.eject then
        if player:getPrimaryHandItem()~=weapon then ISTimedActionQueue.add(ISEquipWeaponAction:new(player,weapon,25,true,weapon:isTwoHandWeapon())); count=count+1 end
        last=ISEjectMagazine:new(player,weapon)
        ISTimedActionQueue.add(last)
        count=count+1
    end
    for _,step in ipairs(plan.steps) do
        local lazy=(step.mode=='install' or step.mode=='replace') and step.tpl or nil
        last=ISTWAssemblyAction:new(player,weapon,step.mode,step.path,nil,lazy)
        ISTimedActionQueue.add(last)
        count=count+1
    end
    if held then
        last=ISEquipWeaponAction:new(player,weapon,25,true,weapon:isTwoHandWeapon())
        ISTimedActionQueue.add(last)
        count=count+1
    end
    self.pending=last
    self:startProgress(count,'build',tpl)
    self.message=getText('IGUI_TW_Msg_buildQueued',#plan.steps)
    self.messageOk=true
    self.queuedMessage=self.message
    self:updateState()
    return true
end
function TWAssemblyWindow:debugSpawn()
    -- Recheck on click too: the server can revoke the role while this screen is open.
    if not self:canDebugSpawn() then return false end
    local inv=self.player:getInventory()
    local function give(tpl)
        local fullType=C.isClip(tpl) and C.clipType(self.key,tpl) or D.items[tpl].pz
        if isClient() then
            -- Same command as ISItemsListTable: server checks AddItem and sends the created item back.
            SendCommandToServer('/additem "'..self.player:getUsername()..'" "'..fullType..'"')
        else
            inv:AddItem(instanceItem(fullType))
        end
    end
    if self.mode=='plan' then
        if not self.plan then return false end
        for _,m in ipairs(self.plan.missing) do
            for _=1,m.count do give(m.tpl) end
        end
    else
        local row=self.candidate
        if not row or row.part then return false end
        give(row.tpl)
    end
    self:refresh(true)
    return true
end

-- Progress of the queued work in the middle of the screen (user's item 2): a bar of the action
-- in progress with a spinning wheel; a build shows its step.
function TWAssemblyWindow:startProgress(total,mode,tpl)
    self.progress={total=math.max(1,total),mode=mode,tpl=tpl,started=getTimestampMs()}
end
function TWAssemblyWindow:progressState()
    local p=self.progress
    if not p or not self.pending then return nil end
    local queue=ISTimedActionQueue.getTimedActionQueue(self.player).queue
    local current=queue[1]
    local remaining=#queue
    local step=math.max(1,math.min(p.total,p.total-remaining+1))
    local delta=0
    if current and current.getJobDelta then
        local ok,value=pcall(current.getJobDelta,current)
        if ok and type(value)=='number' then delta=math.max(0,math.min(1,value)) end
    end
    -- What this step handles: the part it installs, the part it takes off, the item it moves.
    local tpl,name
    if current then
        tpl=current.tpl or current.part and R.meta(current.part) and R.meta(current.part).tpl
        if not tpl and current.weapon and current.path then tpl=R.tree(current.weapon)[current.path] end
        if not tpl and current.item and current.item.getName then name=current.item:getName() end
    end
    tpl=tpl or p.tpl
    local mode=current and current.mode or p.mode
    return {step=step,total=p.total,delta=delta,overall=math.min(1,((step-1)+delta)/p.total),tpl=tpl,name=name,mode=mode}
end
function TWAssemblyWindow:drawProgress()
    local s=self:progressState()
    if not s then return end
    local k=self.k
    local W,H=self.width,self.height
    local removing=s.mode=='remove' or s.mode=='removeBranch'
    local title=getText(removing and 'IGUI_TWK_ProgressRemove' or 'IGUI_TWK_ProgressInstall')
    if s.total>1 then title=getText('IGUI_TWK_ProgressBuild',tostring(s.step),tostring(s.total)) end
    local name=s.tpl and D.items[s.tpl] and U.partName(s.tpl) or s.name or ''
    local px,npx=math.floor(30*k),math.floor(26*k)
    local pad,r=math.floor(30*k),math.floor(46*k)
    local textX=pad+2*r+pad
    -- As wide as the title and the part's full name need, within the screen; a name longer than
    -- that takes a second line.
    local need=math.max(Theme.textWidth(title,px,Theme.FONT_BOLD),Theme.textWidth(name,npx))
    local w=math.ceil(math.max(860*k,math.min(W-80*k,textX+need+pad+1)))
    local lines=Theme.wrap(name,w-textX-pad,npx,2)
    local h=math.floor(170*k+(math.max(1,#lines)-1)*Theme.lineHeight(npx))
    local x,y=math.floor((W-w)/2),math.floor(H*0.5-h/2)
    Theme.fill(self,x,y,w,h,Theme.BLACK,0.82)
    Theme.frame(self,x,y,w,h,Theme.CELL_BORDER,0.9,math.max(1,math.floor(2*k)))
    -- Spinner: a ring with a turning arc (Tarkov's loading wheel).
    local cx,cy=x+pad+r,y+h/2
    local t=getTimestampMs()/1000
    Theme.ring(self,cx,cy,r,math.max(2,math.floor(7*k)),Theme.DIM,0.6)
    Theme.arc(self,cx,cy,r,math.max(2,math.floor(7*k)),t*5.2,t*5.2+math.pi*0.6+math.pi*0.9*s.delta,Theme.PROGRESS,1)
    local tx=x+textX
    Theme.text(self,title,tx,y+math.floor(22*k),px,Theme.WHITE,1,Theme.FONT_BOLD)
    for i,line in ipairs(lines) do
        Theme.text(self,line,tx,y+math.floor(22*k)+Theme.lineHeight(px)+(i-1)*Theme.lineHeight(npx),npx,Theme.MUTED)
    end
    local bw=x+w-tx-pad
    local by=y+h-math.floor(46*k)
    local bh=math.floor(16*k)
    Theme.fill(self,tx,by,bw,bh,Theme.BUTTON,1)
    Theme.fill(self,tx,by,math.floor(bw*s.overall),bh,Theme.PROGRESS,1)
    Theme.frame(self,tx,by,bw,bh,Theme.CELL_BORDER,0.9,1)
    local pct=string.format('%d%%',math.floor(s.overall*100+0.5))
    Theme.text(self,pct,x+w-math.floor(30*k)-Theme.textWidth(pct,math.floor(24*k)),by-Theme.lineHeight(math.floor(24*k))-math.floor(4*k),math.floor(24*k),Theme.WHITE)
end

-- Frame ---------------------------------------------------------------------------------
-- The layer, grid and INFO panel draw from the screen's state. UI optimisers that replay a
-- panel's last drawing until its Lua table changes (pzopt "retained UI") are told about a change
-- by a write to that table; in the vanilla game this costs nothing.
function TWAssemblyWindow:markDrawState()
    local sc=self.scene
    local mx,my=getMouseX(),getMouseY()
    local f=self.info:openFraction()
    local animated=self.pending~=nil or f>0 and f<1
    local state=table.concat({tostring(self.sceneSignature),sc.yaw,sc.pitch,sc.zoom,sc.viewX,sc.viewY,self.mode,tostring(self.selectedPath),
        tostring(self.selectedCandidate),tostring(self.preview and self.preview.key),self.revision or 0,mx,my,tostring(self.grid:getIsVisible()),
        tostring(self.info.open),animated and getTimestampMs() or 0},'|')
    for _,child in ipairs({self.layer,self.grid,self.info}) do
        if child.drawState~=state then child.drawState=state end
    end
    if self.drawState~=state then self.drawState=state end
end
function TWAssemblyWindow:update()
    ISPanel.update(self)
    -- The modding screen changes the weapon, so it lives only while the player carries it (a bag is fine);
    -- the builds screen reads the weapon and closes only when it is out of reach.
    if self.player:isDead() or self.weapon and (self.mode=='live' and not R.inInventory(self.player,self.weapon) or not R.reachable(self.player,self.weapon)) then self:close(); return end
    if self.raisedMenu and not self.raisedMenu:getIsVisible() then self:lowerMenu() end
    local sw,sh=getCore():getScreenWidth(),getCore():getScreenHeight()
    if sw~=self.width or sh~=self.height then
        -- Resolution changed: rebuild at the new size.
        local reopen={self.player,self.weapon,self.mode,self.key}
        self:close()
        TWAssemblyWindow.open(reopen[1],reopen[2],reopen[3],reopen[4])
        return
    end
    self:markDrawState()
    self.tick=self.tick+1
    if isMouseButtonPressed(2) and not (self.middleAt and getTimestampMs()-self.middleAt<250) then self:middleClick() end
    self:applyPreview()
    if self.preview and self.candidateOwner.evicted or not self.preview and self.previewOwner.evicted and self.mode=='plan' then self.sceneSignature=nil; self:updateScene() end
    if self.focusGrid then
        for _,cell in ipairs(self.layer.cells or {}) do if cell.key==self.focusGrid then self:openGrid(cell) end end
        if #(self.layer.cells or {})>0 then self.focusGrid=nil end
    end
    if self.tick%12~=0 then return end
    if self.pending then
        local queued=#ISTimedActionQueue.getTimedActionQueue(self.player).queue>0
        if not queued then
            self.pending=nil
            self.progress=nil
            -- "Build queued: N actions" is over with the work: the screen's own notice (the build
            -- matches the weapon, or what is still missing) takes its place again.
            if self.queuedMessage and self.message==self.queuedMessage then self.message=nil end
            self.queuedMessage=nil
            if self.holdPreview then self.holdPreview=nil; self:clearPreview() end
            self:refresh(true)
        end
    end
    self:refresh(false)
    self:updateState()
end
-- Characteristics for INFO: the build or the weapon, compared with the weapon's default preset;
-- while a part is previewed, the change it makes.
function TWAssemblyWindow:statsRows()
    local signature=tostring(self.revision)..'|'..tostring(self.preview and self.preview.key)..'|'..tostring(self.weapon and self.weapon:getCondition())
    if self.statsCache and self.statsCache.signature==signature then return self.statsCache.bars,self.statsCache.texts end
    local item=self.mode=='live' and self.weapon or self.target
    local ammo=item and TWFirstPersonRecoil and TWFirstPersonRecoil.ammoTpl and TWFirstPersonRecoil.ammoTpl(item) or ''
    local bars,texts=Stats.rows({key=self.key,tree=self.tree,item=item,ammoTpl=ammo,reference=C.presetTree(self.key),
        preview=self.preview and self.preview.tree or nil})
    self.statsCache={signature=signature,bars=bars,texts=texts}
    return bars,texts
end

-- Builds notice at the bottom (Tarkov's green bar): ready, missing parts, no weapon, the draft
-- matches the weapon, a vital part missing; a fresh message of an action replaces it.
function TWAssemblyWindow:notice()
    if self.message then return {ok=self.messageOk~=false,first=self.message} end
    if self.mode~='plan' then return nil end
    if not self.stats.ready then
        local names={}
        for _,path in ipairs(self.stats.missing) do names[#names+1]=Groups.vitalLabel(path,self.key) end
        return {ok=false,first=getText('IGUI_TWK_NoticeVital'),second=table.concat(names,', ')}
    end
    if not self.target then return {ok=false,first=getText('IGUI_TWK_NoticeNoWeapon')} end
    if self.plan.same then return {ok=true,first=getText('IGUI_TWK_NoticeSame')} end
    if #self.plan.missing>0 then
        local names={}
        for _,m in ipairs(self.plan.missing) do names[#names+1]=U.shortName(m.tpl)..(m.count>1 and ' x'..m.count or '') end
        return {ok=false,first=getText('IGUI_TWK_NoticeMissing'),second=table.concat(names,', ')}
    end
    if self.plan.error then return {ok=false,first=U.reasonText(self.plan.error,self.plan.errorDetail,self.plan.errorStep and self.plan.errorStep.tpl)} end
    return {ok=true,first=getText('IGUI_TWK_NoticeReady'),second=getText('IGUI_TWK_NoticeAssemble'),icon=true}
end
function TWAssemblyWindow:drawNotice()
    local n=self:notice()
    if not n then return end
    local k=self.k
    local W,H=self.width,self.height
    local px1,px2=math.floor(34*k),math.floor(32*k)
    local maxW=math.floor(W*0.42)
    local first=Theme.fit(n.first,maxW,px1,Theme.FONT_BOLD)
    local second=n.second and Theme.fit(n.second,maxW,px2) or nil
    local tw=math.max(Theme.textWidth(first,px1,Theme.FONT_BOLD),second and Theme.textWidth(second,px2) or 0)
    local icon=n.icon and math.floor(80*k) or 0
    local w=math.floor(tw+math.floor(36*k)*2+icon+(icon>0 and math.floor(20*k) or 0))
    local h=math.floor((second and 152 or 92)*k)
    local x,y=math.floor((W-w)/2),H-h-math.floor(40*k)
    local plate=n.ok and Theme.NOTICE_OK or Theme.NOTICE_WARN
    Theme.fill(self,x,y,w,h,plate,0.97)
    local ink=n.ok and Theme.BLACK or Theme.WHITE
    local ty=y+(h-(Theme.lineHeight(px1,Theme.FONT_BOLD)+(second and Theme.lineHeight(px2) or 0)))/2
    Theme.text(self,first,x+math.floor(36*k),ty,px1,ink,1,Theme.FONT_BOLD)
    if second then Theme.text(self,second,x+math.floor(36*k),ty+Theme.lineHeight(px1,Theme.FONT_BOLD),px2,ink,1) end
    if icon>0 then Theme.icon(self,'Icon_assemble',x+w-icon-math.floor(30*k),y+(h-icon)/2,icon,icon,Theme.WHITE,0.95) end
end
-- Tooltip of a hovered part in the grid (Tarkov's STATS box) or of a hovered slot cell.
function TWAssemblyWindow:drawTooltip()
    local k=self.k
    local lines={}
    local grid=self.grid
    local px=math.floor(26*k)
    if grid:getIsVisible() and grid.hoverCell and grid.hoverCell.entry then
        local row=grid.hoverCell.entry
        lines[#lines+1]={U.partName(row.tpl),Theme.WHITE,Theme.FONT_BOLD}
        if row.nextTree and not row.installed then
            for _,c in ipairs(Stats.changes(self.key,self.tree,row.nextTree)) do
                lines[#lines+1]={c.label..': '..c.text,c.improved and Theme.NOTICE_OK or Theme.WORSE}
            end
        end
        local status,color
        if row.installed then status,color=getText('IGUI_TWK_Installed'),Theme.WHITE
        elseif not row.allowed and row.reason~='notInInventory' then status,color=U.reasonText(row.reason,row.detail,row.tpl),Theme.WORSE
        elseif row.main>0 then status,color=getText('IGUI_TW_OwnedMain',row.main),Theme.NOTICE_OK
        elseif row.nested>0 then status,color=getText('IGUI_TW_OwnedBag',row.nested),Theme.BAG_BORDER
        else status,color=getText('IGUI_TW_OwnedNone'),Theme.MUTED end
        lines[#lines+1]={status,color}
    elseif self.layer.hover or self.layer.hoverPart then
        local cell=self.layer.hover or self.layer.hoverPart
        local slot=cell.slot
        lines[#lines+1]={U.slotName(slot.path,self.key),Theme.MUTED}
        lines[#lines+1]={slot.tpl and U.partName(slot.tpl) or getText('IGUI_TWK_Empty'),Theme.WHITE,Theme.FONT_BOLD}
    end
    if #lines==0 then return end
    local w=0
    for _,l in ipairs(lines) do w=math.max(w,Theme.textWidth(l[1],px,l[3])) end
    w=math.min(w,self.width*0.4)
    local lh=Theme.lineHeight(px)
    local pad=math.floor(12*k)
    local bw,bh=w+pad*2,#lines*lh+pad*2
    local x,y=getMouseX()+math.floor(24*k),getMouseY()+math.floor(24*k)
    if x+bw>self.width then x=getMouseX()-bw-math.floor(12*k) end
    if y+bh>self.height then y=self.height-bh end
    Theme.fill(self,x,y,bw,bh,Theme.BLACK,0.92)
    Theme.frame(self,x,y,bw,bh,Theme.CELL_BORDER,1,1)
    for i,l in ipairs(lines) do Theme.text(self,Theme.fit(l[1],w,px,l[3]),x+pad,y+pad+(i-1)*lh,px,l[2],1,l[3]) end
end
function TWAssemblyWindow:prerender()
    GamepadBuilder.update(self)
    -- Alive in the UI manager: the guard releases the movement hold of a screen the UI manager no longer draws (it
    -- was taken out of the list by something else, which never reaches close()). prerender, not update: the game
    -- only calls update() a few times a second for this screen while prerender/render run every frame (measured:
    -- 12 updates against 43 frames in 1.2 s), so update() is no liveness signal.
    if self.blockedMovement then self.pumpSeen=TWAssemblyWindow.pump else TWAssemblyWindow.takeBlock(self) end
    local k=self.k
    local W=self.width
    -- The game's own frame behind, frosted (TW/TarkovUI/Frosted).
    Frosted.draw(self,0,0,self.width,self.height)
    -- Title, as Tarkov's: icon, thin capital title, the weapon's full name under it.
    local plan=self.mode=='plan'
    local title=getText(plan and 'IGUI_TWK_TitleBuilds' or 'IGUI_TWK_TitleModding')
    local tpx=math.floor(86*k)
    local tw=Theme.textWidth(title,tpx)
    local icon=math.floor(70*k)
    local tx=math.floor((W-tw)/2+icon/2)
    Theme.icon(self,plan and 'Header_builds' or 'Header_modding',tx-icon-math.floor(26*k),math.floor(42*k),icon,icon,Theme.MUTED,0.9)
    Theme.text(self,title,tx,math.floor(16*k),tpx,Theme.WHITE)
    local name=plan and U.weaponName(self.key) or self.weapon:getName()
    Theme.text(self,name,W/2,math.floor(108*k),math.floor(38*k),Theme.WHITE,1,nil,'centre')
end
function TWAssemblyWindow:render()
    -- The screen's own render comes after its children: a window of the builds menu stays on top.
    if self.modal then return end
    self:drawNotice()
    self:drawProgress()
    self:drawTooltip()
end

return TWAssemblyWindow
