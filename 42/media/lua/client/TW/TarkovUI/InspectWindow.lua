-- Escape from Tarkov's item inspect window for the mod's weapons ("INSPECT" in the item's menu;
-- reference Screenshot_323): a title bar with the magnifier and the close button, the class path
-- and the weight, the weapon in 3D on Tarkov's striped backdrop (drag turns it, the wheel zooms,
-- the right button moves it), the item's actions that exist in Project Zomboid, the
-- characteristics that act in Project Zomboid (TW/TarkovUI/Stats) and the grid of the weapon's
-- slots. The window moves by its title bar and stays open beside the game.
require('ISUI/ISPanel')
local C=require('TW/Core')
local R=require('TW/Runtime')
local Render=require('TW/Render')
local GeometryState=require('TW/GeometryState')
local U=require('TW/UIHelpers')
local Theme=require('TW/TarkovUI/Theme')
local Groups=require('TW/TarkovUI/Groups')
local Stats=require('TW/TarkovUI/Stats')
require('TW/TarkovUI/Scene')
require('TW/TarkovUI/StatsPanel')
local D=C.data

TWInspectWindow=ISPanel:derive('TWInspectWindow')
TWInspectWindow.instances={}

-- Close the inspect windows of a player (the modding screen opens over the game).
function TWInspectWindow.closeFor(player)
    for window in pairs(TWInspectWindow.instances) do
        if window.player==player then window:close() end
    end
end
function TWInspectWindow.open(player,weapon)
    if not (weapon and R.isWeapon(weapon)) then return end
    -- Only an item the player can see: carried (the main inventory or a bag) or in a container the loot window
    -- shows. The window's update closes it when the item leaves that reach.
    if not R.reachable(player,weapon) then return end
    for window in pairs(TWInspectWindow.instances) do
        if window.weapon==weapon then window:bringToTop(); return window end
    end
    local window=TWInspectWindow:new(player,weapon)
    window:initialise()
    window:addToUIManager()
    TWInspectWindow.instances[window]=true
    return window
end

function TWInspectWindow:new(player,weapon)
    local sw,sh=getCore():getScreenWidth(),getCore():getScreenHeight()
    local k=Theme.scale(sh)
    local w=math.min(sw-math.floor(40*k),math.floor(1450*k))
    local o=ISPanel.new(self,0,0,w,math.floor(sh*0.9))
    o.k=k
    o.player=player
    o.playerIndex=player:getPlayerNum()
    o.weapon=weapon
    o.key=R.key(weapon)
    o.background=false
    o.moveWithMouse=false
    o.previewOwner={preview=true}
    o.tick=0
    return o
end

-- Vertical layout at the reference's 4K sizes times k; the 3D view gives way on small screens.
function TWInspectWindow:metrics()
    local k=self.k
    return {title=math.floor(38*k),crumb=math.floor(52*k),preview=math.floor(515*k),button=math.floor(34*k),buttonGap=math.floor(9*k),
        row=math.floor(40*k),rowGap=math.floor(10*k),cell=math.floor(118*k),cellGap=math.floor(6*k),pad=math.floor(26*k)}
end
function TWInspectWindow:actions()
    local w=self.weapon
    local list={}
    local inHands=self.player:getPrimaryHandItem()==w or self.player:getSecondaryHandItem()==w
    list[#list+1]={id='reload',label='IGUI_TWK_ActionReload',icon='Action_reload',menu=true}
    list[#list+1]={id='unload',label='IGUI_TWK_ActionUnload',icon='Action_unload',enabled=w:isContainsClip() or w:getCurrentAmmoCount()>0 or w:isRoundChambered()}
    list[#list+1]={id='equip',label=inHands and 'IGUI_TWK_ActionUnequip' or 'IGUI_TWK_ActionEquip',icon=inHands and 'Action_unequip' or 'Action_equip',
        enabled=inHands or (w:getModData().TW and w:getModData().TW.ready)~=false}
    list[#list+1]={id='modding',label='IGUI_TWK_ActionModding',icon='Action_modding'}
    list[#list+1]={id='build',label='IGUI_TWK_ActionBuild',icon='Action_editbuild'}
    list[#list+1]={id='firemode',label='IGUI_TWK_ActionFireMode',icon='Stat_firemode',menu=true,enabled=w:getFireModePossibilities() and w:getFireModePossibilities():size()>1}
    list[#list+1]={id='favorite',label=w:isFavorite() and 'IGUI_TWK_ActionUnfavorite' or 'IGUI_TWK_ActionFavorite',icon=w:isFavorite() and 'Action_unfavorite' or 'Action_favorite',highlight=true}
    list[#list+1]={id='discard',label='IGUI_TWK_ActionDiscard',icon='Action_discard',danger=true}
    for _,a in ipairs(list) do if a.enabled==nil then a.enabled=true end end
    return list
end
function TWInspectWindow:layout()
    local m=self:metrics()
    local k=self.k
    local sh=getCore():getScreenHeight()
    local bars,texts=self:statsRows()
    local slots=self:slotList()
    local inner=self.width-m.pad*2
    local cols=math.max(1,math.floor((inner+m.cellGap)/(m.cell+m.cellGap)))
    self.gridCols=cols
    local actions=#self:actions()
    local buttonRows=math.ceil(actions/3)
    local fixed=m.title+m.crumb+buttonRows*(m.button+m.buttonGap)+m.pad
        +#bars*(m.row+m.rowGap)+math.ceil(#texts/2)*(m.row+m.rowGap)+m.pad
    local gridRows=math.ceil(#slots/cols)
    local room=sh-math.floor(60*k)
    local preview=m.preview
    local gridH=gridRows*(m.cell+m.cellGap)+m.pad
    -- Too tall: the 3D view shrinks to a third of its size, then the grid scrolls.
    local over=fixed+preview+gridH-room
    if over>0 then preview=math.max(math.floor(m.preview*0.45),preview-over) end
    over=fixed+preview+gridH-room
    self.gridRowsShown=gridRows
    if over>0 then self.gridRowsShown=math.max(1,gridRows-math.ceil(over/(m.cell+m.cellGap))) end
    self.previewH=preview
    self.previewY=m.title+m.crumb
    self.buttonsY=self.previewY+preview+m.pad*0.5
    self.statsY=self.buttonsY+buttonRows*(m.button+m.buttonGap)+m.pad*0.5
    self.gridY=self.statsY+#bars*(m.row+m.rowGap)+math.ceil(#texts/2)*(m.row+m.rowGap)+m.pad*0.5
    local height=self.gridY+self.gridRowsShown*(m.cell+m.cellGap)+m.pad
    self:setHeight(math.min(room,height))
    if self.scene then
        self.scene:setX(math.floor(2*k));self.scene:setY(self.previewY)
        self.scene:setWidth(self.width-math.floor(4*k));self.scene:setHeight(preview)
    end
end
function TWInspectWindow:createChildren()
    self.scene=TWTarkovScene:new(0,0,self.width,10,self)
    self.scene:initialise()
    self:addChild(self.scene)
    self.scene:initialiseScene()
    self:refresh(true)
    local sw,sh=getCore():getScreenWidth(),getCore():getScreenHeight()
    -- Beside the screen's centre, as Tarkov opens it next to the item.
    self:setX(math.max(0,math.floor(sw*0.5-self.width*0.5)))
    self:setY(math.max(0,math.floor((sh-self.height)/2)))
    self:setWantKeyEvents(true)
end
function TWInspectWindow:sceneFitArea()
    local k=self.k
    local w,h=self.scene.width,self.scene.height
    return {x1=w*0.08,y1=h*0.12,x2=w*0.92,y2=h*0.88}
end
function TWInspectWindow:sceneChanged() end

-- The weapon's own render copy, as on the weapon in hands.
function TWInspectWindow:fingerprint()
    local w=self.weapon
    return table.concat({R.fingerprint(w),tostring(w:getCurrentAmmoCount()),tostring(w:isContainsClip()),tostring(w:getCondition()),
        tostring(w:isFavorite()),tostring(self.player:getPrimaryHandItem()==w),tostring(w:getFireMode())},'|')
end
function TWInspectWindow:refresh(force)
    local fp=self:fingerprint()
    if not force and fp==self.lastFingerprint then return end
    self.lastFingerprint=fp
    self.tree,self.entries,self.parts=R.read(self.weapon)
    self.statsCache=nil
    if not Render.current(self.weapon) then R.applyRender(self.weapon) end
    self.tree,self.entries,self.parts=R.read(self.weapon)
    GeometryState.annotate(self.key,self.tree,self.entries)
    local model=self.weapon:getWeaponSprite()
    local signature=model..'|'..Render.fingerprint(self.key,self.entries)
    self:layout()
    if model and signature~=self.sceneSignature then
        self.sceneSignature=signature
        self.scene:setAssembly(self.key,self.tree,self.entries,model,self.sceneSignature~=nil and self.scene.bounds~=nil)
    end
end
function TWInspectWindow:statsRows()
    if self.statsCache then return self.statsCache.bars,self.statsCache.texts end
    local ammo=TWFirstPersonRecoil and TWFirstPersonRecoil.ammoTpl and TWFirstPersonRecoil.ammoTpl(self.weapon) or ''
    local tree=self.tree or R.tree(self.weapon)
    local bars,texts=Stats.rows({key=self.key,tree=tree,item=self.weapon,ammoTpl=ammo,reference=C.presetTree(self.key)})
    self.statsCache={bars=bars,texts=texts}
    return bars,texts
end
-- Slots of the weapon for the bottom grid: every slot of the assembly, fitted or empty.
function TWInspectWindow:slotList()
    return C.slotTree(self.key,self.tree or R.tree(self.weapon))
end

function TWInspectWindow:close()
    if self.scene then self.scene:endDrag() end
    self:setVisible(false)
    self:removeFromUIManager()
    TWInspectWindow.instances[self]=nil
end
function TWInspectWindow:isKeyConsumed(key) return key==Keyboard.KEY_ESCAPE end
function TWInspectWindow:onKeyRelease(key)
    if key==Keyboard.KEY_ESCAPE then self:close(); return true end
end
function TWInspectWindow:update()
    ISPanel.update(self)
    -- The window follows the item: it closes when the item is gone from the player's reach (dropped far
    -- away, its container closed or out of reach), not because it is in a bag or a nearby crate.
    if self.player:isDead() or not R.reachable(self.player,self.weapon) then self:close(); return end
    self.tick=self.tick+1
    local fp=self:fingerprint()
    if self.drawState~=fp..'|'..tostring(self.scene.revision)..'|'..getMouseX()..'|'..getMouseY() then
        self.drawState=fp..'|'..tostring(self.scene.revision)..'|'..getMouseX()..'|'..getMouseY()
    end
    if self.tick%10==0 then self:refresh(false) end
end

-- Action buttons in three columns (Tarkov's item menu inside the window).
function TWInspectWindow:buttonRects()
    local m=self:metrics()
    local inner=self.width-m.pad*2
    local w=math.floor((inner-2*m.buttonGap*2)/3)
    local rects={}
    for i,a in ipairs(self:actions()) do
        local c=(i-1)%3
        local r=math.floor((i-1)/3)
        rects[i]={action=a,x=m.pad+c*(w+m.buttonGap*2),y=self.buttonsY+r*(m.button+m.buttonGap),w=w,h=m.button}
    end
    return rects
end
function TWInspectWindow:gridRects()
    local m=self:metrics()
    local rects={}
    local slots=self:slotList()
    local shown=self.gridRowsShown*self.gridCols
    local start=(self.gridScroll or 0)*self.gridCols
    for i=1,math.min(#slots-start,shown) do
        local c=(i-1)%self.gridCols
        local r=math.floor((i-1)/self.gridCols)
        rects[#rects+1]={slot=slots[start+i],x=m.pad+c*(m.cell+m.cellGap),y=self.gridY+r*(m.cell+m.cellGap),w=m.cell,h=m.cell}
    end
    return rects
end
local function inside(r,x,y) return x>=r.x and x<r.x+r.w and y>=r.y and y<r.y+r.h end
-- Tarkov's empty slot silhouettes (slots/<name>); numbered and special slots use their base kind.
local SILHOUETTES={'mod_pistol_grip','mod_pistolgrip','mod_sight_front','mod_sight_rear','mod_gas_block','mod_mag_shaft','mod_handguard',
    'mod_flashlight','mod_magazine','mod_reciever','mod_launcher','mod_tactical','mod_foregrip','mod_trigger','mod_barrel','mod_hammer',
    'mod_charge','mod_muzzle','mod_scope','mod_mount','mod_stock','mod_bipod','mod_catch'}
local function silhouette(path)
    local name=C.slot(path) or path
    for _,base in ipairs(SILHOUETTES) do
        if name:sub(1,#base)==base then return 'Slot_'..base end
    end
end

function TWInspectWindow:prerender()
    local k=self.k
    local m=self:metrics()
    local W,H=self.width,self.height
    -- Window: black body with Tarkov's soft frame.
    Theme.fill(self,0,0,W,H,Theme.BLACK,0.96)
    Theme.frame(self,0,0,W,H,Theme.CELL_BORDER,0.9,math.max(1,math.floor(2*k)))
    -- Title bar: magnifier, the item's name, the close button.
    Theme.fill(self,0,0,W,m.title,Theme.BLACK,1)
    local icon=math.floor(26*k)
    Theme.icon(self,'Inspect_search',math.floor(10*k),(m.title-icon)/2,icon,icon,Theme.WHITE,1)
    local close=self:closeRect()
    local px=math.floor(26*k)
    Theme.text(self,Theme.fit(self.weapon:getName(),close.x-math.floor(60*k),px),math.floor(46*k),(m.title-Theme.lineHeight(px))/2,px,Theme.WHITE)
    local hoverClose=self:isMouseOver() and inside(close,self:getMouseX(),self:getMouseY())
    if not Theme.icon(self,'Inspect_close',close.x,close.y,close.w,close.h,hoverClose and Theme.WHITE or Theme.TEXT,1) then
        Theme.fill(self,close.x,close.y,close.w,close.h,Theme.NOTICE_WARN,1)
    end
    Theme.icon(self,'Inspect_closex',close.x+close.w*0.3,close.y+close.h*0.2,close.w*0.4,close.h*0.6,Theme.WHITE,1)
    -- Class path and weight.
    local cpx=math.floor(24*k)
    local crumb=getText('IGUI_TWK_CrumbWeapons')..' > '..Stats.className(self.key)
    Theme.text(self,crumb,m.pad*0.6,m.title+(m.crumb-Theme.lineHeight(cpx))/2,cpx,Theme.MUTED)
    local weight=string.format('%.3f',self.weapon:getActualWeight()+self.weapon:getContentsWeight())..' '..getText('IGUI_TWK_UnitKg')
    local wpx=math.floor(30*k)
    local wx=W-m.pad*0.6-Theme.textWidth(weight,wpx)
    Theme.text(self,weight,wx,m.title+(m.crumb-Theme.lineHeight(wpx))/2,wpx,Theme.WHITE)
    Theme.icon(self,'Inspect_mass',wx-math.floor(34*k),m.title+(m.crumb-math.floor(30*k))/2,math.floor(26*k),math.floor(30*k),Theme.WHITE,1)
    -- 3D backdrop: Tarkov's striped sprite over black, the corner stripes.
    local y0,ph=self.previewY,self.previewH
    Theme.fill(self,0,y0,W,ph,Theme.STRIPE_A,1)
    if not Theme.icon(self,'Inspect_back',0,y0,W,ph,Theme.WHITE,0.9) then Theme.stripes(self,0,y0,W,ph,1) end
    Theme.icon(self,'Inspect_corner_tr',W-math.floor(150*k),y0,math.floor(150*k),math.floor(146*k),Theme.WHITE,0.9)
    Theme.icon(self,'Inspect_corner_bl',0,y0+ph-math.floor(236*k),math.floor(240*k),math.floor(236*k),Theme.WHITE,0.9)
end
function TWInspectWindow:closeRect()
    local m=self:metrics()
    local k=self.k
    local w,h=math.floor(46*k),math.floor(29*k)
    return {x=self.width-w-math.floor(6*k),y=(m.title-h)/2,w=w,h=h}
end
function TWInspectWindow:render()
    local k=self.k
    local m=self:metrics()
    local mx,my=self:getMouseX(),self:getMouseY()
    local over=self:isMouseOver()
    -- Action buttons.
    local bpx=math.floor(22*k)
    for _,r in ipairs(self:buttonRects()) do
        local a=r.action
        local hover=over and a.enabled and inside(r,mx,my)
        local plate=a.danger and Theme.BUTTON_DANGER or a.highlight and {r=0.10,g=0.20,b=0.30} or Theme.BUTTON
        if hover then plate=a.danger and {r=0.45,g=0.08,b=0.08} or Theme.BUTTON_HOVER end
        Theme.fill(self,r.x,r.y,r.w,r.h,plate,a.enabled and 0.97 or 0.5)
        local icon=math.floor(r.h*0.66)
        Theme.icon(self,a.icon,r.x+math.floor(14*k),r.y+(r.h-icon)/2,icon,icon,a.enabled and Theme.WHITE or Theme.DIM,1)
        Theme.text(self,Theme.fit(getText(a.label),r.w-icon-math.floor(70*k),bpx),r.x+icon+math.floor(32*k),r.y+(r.h-Theme.lineHeight(bpx))/2,bpx,a.enabled and Theme.WHITE or Theme.DIM)
        if a.menu then
            local s=r.h*0.16
            local cx,cy=r.x+r.w-math.floor(18*k),r.y+r.h/2
            Theme.quad(self,cx-s,cy-s*1.3,cx+s,cy,cx+s,cy,cx-s,cy+s*1.3,a.enabled and Theme.WHITE or Theme.DIM,1)
        end
    end
    -- Characteristics: bars in one column, the text rows in two (as the reference).
    local bars,texts=self:statsRows()
    TWTarkovStatsPanel.drawRows(self,bars,texts,m.pad,self.statsY,self.width-m.pad*2,m.row,m.rowGap,k,2)
    -- Slot grid: fitted parts with their badge, empty slots as Tarkov's silhouettes.
    local px=math.max(9,math.floor(21*k))
    local hovered
    for _,r in ipairs(self:gridRects()) do
        local slot=r.slot
        local hover=over and inside(r,mx,my)
        if hover then hovered=r end
        Theme.fill(self,r.x,r.y,r.w,r.h,slot.tpl and Theme.GRID_CELL or Theme.CELL_EMPTY,0.97)
        Theme.frame(self,r.x,r.y,r.w,r.h,hover and Theme.CELL_HOVER or Theme.GRID_BORDER,1,math.max(1,math.floor(2*k)))
        local pad=math.floor(6*k)
        if slot.tpl then
            local icon=U.icon(slot.tpl)
            if icon then self:drawTextureScaledAspect2(icon,r.x+pad,r.y+Theme.lineHeight(px),r.w-pad*2,r.h-Theme.lineHeight(px)-pad*2,1,1,1,1) end
            -- Short name in the top right corner, on two lines when long (Tarkov's item cell).
            for i,line in ipairs(Theme.wrap(U.shortName(slot.tpl),r.w-pad*2,px,2)) do
                Theme.text(self,line,r.x+r.w-pad-Theme.textWidth(line,px),r.y+pad*0.4+(i-1)*Theme.lineHeight(px)*0.86,px,Theme.WHITE)
            end
            Groups.badge(self,slot.tpl,r.x+math.floor(3*k),r.y+r.h-math.floor(27*k),math.floor(24*k))
            local rounds=slot.path=='mod_magazine' and C.isClip(slot.tpl) and self.weapon:isContainsClip() and
                (tostring(self.weapon:getCurrentAmmoCount())..'/'..tostring(self.weapon:getMaxAmmo())) or nil
            if rounds then Theme.outlinedText(self,rounds,r.x+r.w-pad-Theme.textWidth(rounds,px),r.y+r.h-Theme.lineHeight(px)-pad*0.3,px,Theme.WHITE,1) end
        else
            local art=silhouette(slot.path)
            if not (art and Theme.icon(self,art,r.x+pad*2,r.y+pad*2,r.w-pad*4,r.h-pad*4,Theme.WHITE,0.55)) then
                Groups.drawSlotGlyphs(self,slot,r.x+pad,r.y+r.h/2,math.floor(22*k),Theme.MUTED,0.8)
            end
            local name=Theme.fit(Groups.slotLabel(slot.path,self.key),r.w-pad*2,px)
            Theme.text(self,name,r.x+(r.w-Theme.textWidth(name,px))/2,r.y+pad*0.4,px,Theme.TEXT)
        end
    end
    if hovered then
        local slot=hovered.slot
        local text=U.slotName(slot.path,self.key)..': '..(slot.tpl and U.partName(slot.tpl) or getText('IGUI_TWK_Empty'))
        local tpx=math.floor(24*k)
        local w=math.min(self.width*0.9,Theme.textWidth(text,tpx)+math.floor(24*k))
        local x=math.max(0,math.min(self.width-w,mx+math.floor(18*k)))
        local y=math.max(0,my-Theme.lineHeight(tpx)-math.floor(24*k))
        Theme.fill(self,x,y,w,Theme.lineHeight(tpx)+math.floor(12*k),Theme.BLACK,0.92)
        Theme.frame(self,x,y,w,Theme.lineHeight(tpx)+math.floor(12*k),Theme.CELL_BORDER,1,1)
        Theme.text(self,Theme.fit(text,w-math.floor(24*k),tpx),x+math.floor(12*k),y+math.floor(6*k),tpx,Theme.WHITE)
    end
end

function TWInspectWindow:runAction(a)
    local p,w=self.player,self.weapon
    if a.id=='modding' then self:close(); TWAssemblyWindow.open(p,w); return end
    if a.id=='build' then self:close(); TWAssemblyWindow.open(p,w,'plan'); return end
    if a.id=='reload' then
        local menu=ISContextMenu.get(self.playerIndex,getMouseX(),getMouseY())
        ISInventoryPaneContextMenu.doReloadMenuForWeapon(p,w,menu)
        return
    end
    if a.id=='firemode' then
        local menu=ISContextMenu.get(self.playerIndex,getMouseX(),getMouseY())
        for i=0,w:getFireModePossibilities():size()-1 do
            local mode=w:getFireModePossibilities():get(i)
            local option=menu:addOption(getText('ContextMenu_FireMode_'..mode),p,ISInventoryPaneContextMenu.onChangefiremode,w,mode)
            if mode==w:getFireMode() then option.checkMark=true end
        end
        return
    end
    if a.id=='unload' then
        if w:isContainsClip() then ISInventoryPaneContextMenu.onEjectMagazine(p,w)
        else ISInventoryPaneContextMenu.onUnloadBulletsFromFirearm(p,w) end
        return
    end
    if a.id=='equip' then
        if p:getPrimaryHandItem()==w or p:getSecondaryHandItem()==w then ISInventoryPaneContextMenu.unequipItem(w,self.playerIndex)
        else ISInventoryPaneContextMenu.equipWeapon(w,true,w:isTwoHandWeapon(),self.playerIndex) end
        return
    end
    -- Vanilla's own favourite path (ISInventoryPaneContextMenu.onFavorite): the second argument
    -- syncs the flag to the server in multiplayer; without it the flag stayed on this client only.
    if a.id=='favorite' then w:setFavorite(not w:isFavorite(),true); self:refresh(true); return end
    if a.id=='discard' then
        ISInventoryPaneContextMenu.onDropItems({w},self.playerIndex)
        self:close()
        return
    end
end
function TWInspectWindow:onMouseDown(x,y)
    local m=self:metrics()
    if inside(self:closeRect(),x,y) then self.pressedClose=true; return true end
    if y<m.title then self.dragging=true; self.dragX,self.dragY=getMouseX()-self:getX(),getMouseY()-self:getY(); self:setCapture(true); return true end
    for _,r in ipairs(self:buttonRects()) do
        if inside(r,x,y) then self.pressed=r.action.id; return true end
    end
    for _,r in ipairs(self:gridRects()) do
        if inside(r,x,y) then self.pressedSlot=r.slot.path; return true end
    end
    return true
end
function TWInspectWindow:onMouseMove(dx,dy)
    if self.dragging then
        local sw,sh=getCore():getScreenWidth(),getCore():getScreenHeight()
        self:setX(math.max(-self.width*0.8,math.min(sw-self.width*0.2,getMouseX()-self.dragX)))
        self:setY(math.max(0,math.min(sh-self:metrics().title,getMouseY()-self.dragY)))
    end
end
function TWInspectWindow:onMouseMoveOutside(dx,dy) self:onMouseMove(dx,dy) end
function TWInspectWindow:onMouseUp(x,y)
    if self.dragging then self.dragging=false; self:setCapture(false); return true end
    if self.pressedClose then
        self.pressedClose=false
        if inside(self:closeRect(),x,y) then self:close() end
        return true
    end
    local pressed=self.pressed
    self.pressed=nil
    if pressed then
        for _,r in ipairs(self:buttonRects()) do
            if r.action.id==pressed and inside(r,x,y) and r.action.enabled then self:runAction(r.action) end
        end
        return true
    end
    local slot=self.pressedSlot
    self.pressedSlot=nil
    if slot then
        for _,r in ipairs(self:gridRects()) do
            if r.slot.path==slot and inside(r,x,y) then
                self:close()
                local screen=TWAssemblyWindow.open(self.player,self.weapon)
                if screen then screen.focusGrid=slot end
            end
        end
    end
    return true
end
function TWInspectWindow:onMouseUpOutside()
    if self.dragging then self.dragging=false; self:setCapture(false) end
    self.pressed,self.pressedSlot,self.pressedClose=nil,nil,nil
end
function TWInspectWindow:onMouseWheel(delta)
    if self.gridRowsShown and self:getMouseY()>=self.gridY then
        local rows=math.ceil(#self:slotList()/self.gridCols)
        self.gridScroll=math.max(0,math.min(rows-self.gridRowsShown,(self.gridScroll or 0)+delta))
    end
    return true
end

return TWInspectWindow
