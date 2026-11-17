-- Windows of the builds screen, as Tarkov's (user's report of 21.10.2026, items 1, 2 and 4):
--   OPEN ...       the saved builds of every weapon and Tarkov's presets in Tarkov's tree of weapon
--                  classes (EFT OpenBuildWindow, BuildsCategoriesPanel), and a field for a build code;
--   SELECT WEAPON  any weapon of the mod, to look at and plan (EFT SelectWeaponBodyWindow);
--   PUBLISH ...    the build's code, copied to the clipboard, which opens the same build in any game.
-- They are children of the screen and so drawn over it. The game's context menus the screen opened
-- before were drawn under it: the screen is always on top, and UIManager moves always-on-top
-- elements behind everything else every frame, so OPEN and SELECT WEAPON seemed to do nothing.
require('ISUI/ISPanel')
require('ISUI/ISTextEntryBox')
require('TW/TarkovUI/Widgets')
local C=require('TW/Core')
local R=require('TW/Runtime')
local B=require('TW/Builds')
local U=require('TW/UIHelpers')
local Theme=require('TW/TarkovUI/Theme')
local Groups=require('TW/TarkovUI/Groups')
local S=require('TW/TarkovUI/Search')
local D=C.data

local DOUBLE_CLICK=450

-- A text field of the windows: the largest game font that fits its height.
local function fontFor(h)
    local tm=getTextManager()
    for _,font in ipairs({UIFont.Large,UIFont.Medium,UIFont.Small}) do
        if tm:getFontHeight(font)+6<=h then return font end
    end
    return UIFont.Small
end
local function textField(owner,x,y,w,h,placeholder,onChange)
    local box=ISTextEntryBox:new('',x,y,w,h)
    box.font=fontFor(h)
    box:initialise()
    box.backgroundColor={r=0,g=0,b=0,a=0.7}
    box.borderColor={r=Theme.NAME_FIELD.r,g=Theme.NAME_FIELD.g,b=Theme.NAME_FIELD.b,a=0.9}
    box:setPlaceholderText(placeholder)
    if onChange then box.onTextChangeFunction=onChange; box.target=owner end
    owner:addChild(box)
    return box
end
local function button(owner,x,y,h,handler,label,kind)
    local b=TWTarkovPlateButton:new(x,y,h,owner,handler,label,kind)
    b:initialise()
    owner:addChild(b)
    return b
end
local function presetLabel(name) return (tostring(name):gsub('_',' ')) end

-- Modal window ---------------------------------------------------------------------------------
-- Covers the whole screen (dim), the window in the middle; a press outside closes it, Esc too
-- (TWAssemblyWindow:onKeyRelease). Subclasses build their content in createContent().
TWTarkovModal=ISPanel:derive('TWTarkovModal')
function TWTarkovModal:new(editor,title,w,h)
    local o=ISPanel.new(self,0,0,editor.width,editor.height)
    o.editor,o.k,o.title=editor,editor.k,title
    o.background=false
    local k=editor.k
    local margin=math.floor(24*k)
    o.boxW=math.min(math.floor(w*k),editor.width-2*margin)
    o.boxH=math.min(math.floor(h*k),editor.height-2*margin)
    o.boxX=math.floor((editor.width-o.boxW)/2)
    o.boxY=math.floor((editor.height-o.boxH)/2)
    o.head=math.floor(76*k)
    o.pad=math.floor(26*k)
    o.lineH=math.max(getTextManager():getFontHeight(UIFont.Small)+8,math.floor(54*k))
    o.gap=math.floor(14*k)
    return o
end
function TWTarkovModal:createChildren()
    ISPanel.createChildren(self)
    local k=self.k
    local size=self.head-math.floor(22*k)
    self.closeButton=button(self,0,0,size,self.cancel,'X')
    self.closeButton:setWidth(size)
    self.closeButton:setX(self.boxX+self.boxW-size-math.floor(11*k))
    self.closeButton:setY(self.boxY+math.floor(11*k))
    self:createContent()
end
function TWTarkovModal:createContent() end
function TWTarkovModal:inside(x,y)
    return x>=self.boxX and y>=self.boxY and x<self.boxX+self.boxW and y<self.boxY+self.boxH
end
function TWTarkovModal:prerender()
    local k=self.k
    Theme.fill(self,0,0,self.width,self.height,Theme.BLACK,0.55)
    local x,y,w,h=self.boxX,self.boxY,self.boxW,self.boxH
    Theme.fill(self,x,y,w,h,Theme.CELL,0.97)
    Theme.fill(self,x,y,w,self.head,Theme.BUTTON,0.98)
    Theme.frame(self,x,y,w,h,Theme.CELL_BORDER,0.95,math.max(1,math.floor(2*k)))
    local px=math.floor(34*k)
    Theme.text(self,Theme.fit(self.title,w-self.pad*2-self.head,px,Theme.FONT_BOLD),x+self.pad,y+(self.head-Theme.lineHeight(px,Theme.FONT_BOLD))/2,px,Theme.WHITE,1,Theme.FONT_BOLD)
end
-- A line of text inside the window (the error or hint under the controls).
function TWTarkovModal:note(text,y,color,px)
    px=px or math.floor(24*self.k)
    local lines=Theme.wrap(text,self.boxW-self.pad*2,px,3)
    for i,line in ipairs(lines) do Theme.text(self,line,self.boxX+self.pad,y+(i-1)*Theme.lineHeight(px),px,color or Theme.MUTED) end
end
function TWTarkovModal:onMouseDown(x,y) self.outside=not self:inside(x,y); return true end
function TWTarkovModal:onMouseUp(x,y)
    if self.outside and not self:inside(x,y) then self:cancel() end
    self.outside=nil
    return true
end
function TWTarkovModal:onRightMouseDown() return true end
function TWTarkovModal:onRightMouseUp() return true end
function TWTarkovModal:onMouseWheel(delta)
    if self.list and self.list:getIsVisible() then return self.list:onMouseWheel(delta) end
    return true
end
function TWTarkovModal:fields() return {} end
function TWTarkovModal:cancel() self:close() end
function TWTarkovModal:close()
    for _,field in ipairs(self:fields()) do if field:isFocused() then field:unfocus() end end
    self:setVisible(false)
    if self.editor.modal==self then self.editor.modal=nil end
    self.editor:removeChild(self)
end

-- Scrolling list ---------------------------------------------------------------------------------
-- Rows of different heights drawn by the owner (owner:drawRow(list, row, x, y, w, h, hovered,
-- selected)); a press calls owner:pressRow(row, x, y). Wheel and the bar on the right scroll it.
TWTarkovScrollList=ISPanel:derive('TWTarkovScrollList')
function TWTarkovScrollList:new(x,y,w,h,owner)
    local o=ISPanel.new(self,x,y,w,h)
    o.owner,o.k=owner,owner.k
    o.background=false
    o.rows={}
    o.total=0
    o.scrollY=0
    o.barW=math.max(6,math.floor(14*owner.k))
    return o
end
-- rows: {h=..., ...}; their y in the list are set here.
function TWTarkovScrollList:setRows(rows,keepScroll)
    local y=0
    for _,row in ipairs(rows) do row.y=y; y=y+row.h end
    self.rows,self.total=rows,y
    if not keepScroll then self.scrollY=0 end
    self:clampScroll()
end
function TWTarkovScrollList:clampScroll()
    self.scrollY=math.max(0,math.min(self.scrollY,self.total-self.height))
end
function TWTarkovScrollList:reveal(row)
    if not row then return end
    if row.y<self.scrollY then self.scrollY=row.y
    elseif row.y+row.h>self.scrollY+self.height then self.scrollY=row.y+row.h-self.height end
    self:clampScroll()
end
function TWTarkovScrollList:rowAt(y)
    local yy=y+self.scrollY
    for _,row in ipairs(self.rows) do
        if yy>=row.y and yy<row.y+row.h then return row end
    end
end
function TWTarkovScrollList:hasBar() return self.total>self.height end
function TWTarkovScrollList:prerender()
    local k=self.k
    local w,h=self.width,self.height
    Theme.fill(self,0,0,w,h,Theme.BLACK,0.45)
    local inner=w-(self:hasBar() and self.barW+math.floor(4*k) or 0)
    local mx,my=self:getMouseX(),self:getMouseY()
    local hover=self:isMouseOver() and mx<inner and self:rowAt(my) or nil
    self:setStencilRect(0,0,w,h)
    for _,row in ipairs(self.rows) do
        local y=row.y-self.scrollY
        if y+row.h>0 and y<h then self.owner:drawRow(self,row,0,y,inner,row.h,row==hover) end
    end
    self:clearStencilRect()
    if self:hasBar() then
        local bar=math.max(math.floor(30*k),h*h/self.total)
        local pos=self.scrollY/(self.total-h)*(h-bar)
        Theme.fill(self,w-self.barW,0,self.barW,h,Theme.BUTTON,0.8)
        Theme.fill(self,w-self.barW,pos,self.barW,bar,Theme.HANDLE,0.9)
    end
    Theme.frame(self,0,0,w,h,Theme.CELL_BORDER,0.8,1)
end
function TWTarkovScrollList:onMouseDown(x,y)
    if self:hasBar() and x>=self.width-self.barW-math.floor(4*self.k) then
        self.dragBar=true
        self:onMouseMove(0,0)
        return true
    end
    local row=self:rowAt(y)
    if row then self.owner:pressRow(row,x,y+self.scrollY-row.y) end
    return true
end
function TWTarkovScrollList:onMouseMove(dx,dy)
    if not self.dragBar then return end
    local h=self.height
    local bar=math.max(math.floor(30*self.k),h*h/self.total)
    local f=(self:getMouseY()-bar/2)/math.max(1,h-bar)
    self.scrollY=f*(self.total-h)
    self:clampScroll()
end
function TWTarkovScrollList:onMouseMoveOutside(dx,dy) self:onMouseMove(dx,dy) end
function TWTarkovScrollList:onMouseUp() self.dragBar=nil; return true end
function TWTarkovScrollList:onMouseUpOutside() self.dragBar=nil end
function TWTarkovScrollList:onMouseWheel(delta)
    self.scrollY=self.scrollY+delta*math.floor(120*self.k)
    self:clampScroll()
    return true
end

-- Small drawing helpers of the rows.
local function arrow(ui,x,y,size,open,c)
    if open then Theme.quad(ui,x,y,x+size,y,x+size/2,y+size*0.6,x+size/2,y+size*0.6,c,0.9)
    else Theme.quad(ui,x,y,x+size*0.6,y+size/2,x+size*0.6,y+size/2,x,y+size,c,0.9) end
end
local function badge(ui,text,x,y,px,color)
    local w=Theme.textWidth(text,px,Theme.FONT_BOLD)+px*0.8
    local h=Theme.lineHeight(px,Theme.FONT_BOLD)
    Theme.fill(ui,x,y,w,h,color,0.85)
    Theme.text(ui,text,x+px*0.4,y,px,Theme.BLACK,1,Theme.FONT_BOLD)
    return w
end

-- OPEN ... -----------------------------------------------------------------------------------------
-- Weapon classes > weapons > builds (saved ones first, then Tarkov's presets); the weapon on the
-- screen and every weapon with saved builds open. Click selects, a double click or OPEN loads it
-- (another weapon switches the screen to it). Below: the build code field.
TWTarkovOpenWindow=TWTarkovModal:derive('TWTarkovOpenWindow')
function TWTarkovOpenWindow:new(editor)
    local o=TWTarkovModal.new(self,editor,getText('IGUI_TWK_OpenTitle'),1500,1640)
    o.openClass,o.openWeapon={},{}
    return o
end
function TWTarkovOpenWindow:catalog()
    local e=self.editor
    local saved={}
    for _,key in ipairs(B.weapons(e.player)) do saved[key]=B.list(e.player,key) end
    local classes={}
    for key,weapon in pairs(D.weapons) do
        local entries={}
        if key==e.planKey then
            if e:planTarget() then entries[#entries+1]={kind='current',label=getText('IGUI_TWK_BaseCurrent')} end
            entries[#entries+1]={kind='empty',label=getText('IGUI_TWK_BaseEmpty')}
        end
        for _,build in ipairs(saved[key] or {}) do entries[#entries+1]={kind='saved',name=build.name,label=build.name} end
        local names={}
        for name in pairs(weapon.presets or {}) do names[#names+1]=name end
        table.sort(names)
        for _,name in ipairs(names) do entries[#entries+1]={kind='preset',name=name,label=presetLabel(name)} end
        if #entries>0 then
            for _,entry in ipairs(entries) do entry.key=key; entry.text=S.text(entry.label) end
            -- Revolvers are Tarkov's pistols: one heading per shown name.
            local label=Groups.className(Groups.weaponClass(key))
            local short,name=U.weaponShort(key),U.weaponName(key)
            classes[label]=classes[label] or {label=label,weapons={}}
            table.insert(classes[label].weapons,{key=key,short=short,name=name,tpl=weapon.tpl,entries=entries,saved=#(saved[key] or {}),text=S.text(short,name,key,label)})
            if #(saved[key] or {})>0 or key==e.planKey then self.openClass[label]=true; self.openWeapon[key]=true end
        end
    end
    local order={}
    for _,class in ipairs(Groups.CLASS_ORDER) do
        local c=classes[Groups.className(class)]
        if c and not c.ordered then
            c.ordered=true
            table.sort(c.weapons,function(a,b) return a.short<b.short end)
            order[#order+1]=c
        end
    end
    self.classes=order
end
function TWTarkovOpenWindow:createContent()
    local k,p,lh,g=self.k,self.pad,self.lineH,self.gap
    local x,y,w=self.boxX+p,self.boxY+self.head+p,self.boxW-2*p
    local icon=lh
    self.search=textField(self,x+icon+g,y,w-icon-g,lh,getText('IGUI_TWK_SearchName'),function() self:rebuild() end)
    self.searchIconAt={x=x,y=y,size=icon}
    -- Bottom up: the main buttons, the hint/error, the code row.
    local bottom=self.boxY+self.boxH-p
    local by=bottom-lh
    self.cancelButton=button(self,0,by,lh,self.cancel,getText('IGUI_TWK_Cancel'))
    self.cancelButton:setX(self.boxX+self.boxW-p-self.cancelButton.width)
    self.openButton=button(self,0,by,lh,self.openSelected,getText('IGUI_TWK_OpenButton'),'accept')
    self.openButton:setX(self.cancelButton:getX()-g-self.openButton.width)
    self.deleteButton=button(self,x,by,lh,self.deleteSelected,getText('IGUI_TWK_DeleteSaved'),'danger')
    self.noteY=by-g-math.floor(Theme.lineHeight(math.floor(24*k))*2)
    local cy=self.noteY-g-lh
    self.codeLabelAt={x=x,y=cy}
    local label=getText('IGUI_TWK_CodeLabel')
    local labelW=math.ceil(Theme.textWidth(label,math.floor(26*k)))+g
    self.codeOpen=button(self,0,cy,lh,self.openCode,getText('IGUI_TWK_OpenCode'))
    self.codeOpen:setX(self.boxX+self.boxW-p-self.codeOpen.width)
    self.codePaste=button(self,0,cy,lh,self.pasteClipboard,getText('IGUI_TWK_PasteButton'))
    self.codePaste:setX(self.codeOpen:getX()-g-self.codePaste.width)
    self.code=textField(self,x+labelW,cy,math.max(math.floor(80*k),self.codePaste:getX()-g-x-labelW),lh,getText('IGUI_TWK_CodePlaceholder'))
    self.list=TWTarkovScrollList:new(x,y+lh+g,w,math.max(lh,cy-g-(y+lh+g)),self)
    self.list:initialise()
    self:addChild(self.list)
    self:catalog()
    self:rebuild()
    for _,row in ipairs(self.list.rows) do
        if row.kind=='weapon' and row.key==self.editor.planKey then self.list.scrollY=row.y; self.list:clampScroll() end
    end
end
function TWTarkovOpenWindow:fields() return {self.search,self.code} end
function TWTarkovOpenWindow:rebuild(keepScroll)
    local k=self.k
    local query=S.query(self.search:getText())
    local rows={}
    local hc,hw,he=math.floor(58*k),math.floor(84*k),math.floor(50*k)
    self.weaponH=hw
    for _,class in ipairs(self.classes or {}) do
        local shown={}
        for _,weapon in ipairs(class.weapons) do
            local all=S.matches(query,weapon.text)
            local entries={}
            for _,entry in ipairs(weapon.entries) do if all or S.matches(query,entry.text) then entries[#entries+1]=entry end end
            if #entries>0 then shown[#shown+1]={weapon=weapon,entries=entries} end
        end
        if #shown>0 then
            local open=query~=nil or self.openClass[class.label]
            rows[#rows+1]={kind='class',label=class.label,count=#shown,open=open,h=hc}
            if open then
                for _,item in ipairs(shown) do
                    local w=item.weapon
                    local wopen=query~=nil or self.openWeapon[w.key]
                    rows[#rows+1]={kind='weapon',weapon=w,key=w.key,count=#item.entries,open=wopen,h=hw}
                    if wopen then
                        for _,entry in ipairs(item.entries) do rows[#rows+1]={kind='entry',entry=entry,key=w.key,h=he} end
                    end
                end
            end
        end
    end
    self.list:setRows(rows,keepScroll)
    if self.selected then
        local still=false
        for _,row in ipairs(rows) do if row.entry==self.selected then still=true end end
        if not still then self.selected=nil end
    end
end
function TWTarkovOpenWindow:drawRow(ui,row,x,y,w,h,hovered)
    local k=self.k
    local pad=math.floor(14*k)
    if row.kind=='class' then
        Theme.fill(ui,x,y+1,w,h-2,hovered and Theme.BUTTON_HOVER or Theme.BUTTON,0.95)
        local size=math.floor(h*0.32)
        arrow(ui,x+pad,y+(h-size)/2,size,row.open,Theme.TEXT)
        local px=math.floor(28*k)
        Theme.text(ui,row.label:upper(),x+pad*2+size,y+(h-Theme.lineHeight(px,Theme.FONT_BOLD))/2,px,Theme.WHITE,1,Theme.FONT_BOLD)
        Theme.text(ui,tostring(row.count),x+w-pad,y+(h-Theme.lineHeight(px))/2,px,Theme.MUTED,1,nil,'right')
        return
    end
    -- Weapon rows: arrow, icon, short and full name; their builds are written under the names.
    local hw=self.weaponH
    local size=math.floor(hw*0.26)
    local iconW=math.floor(hw*2.2)
    local ix=x+pad*2
    local tx=ix+size+pad*2+iconW
    if row.kind=='weapon' then
        local wpn=row.weapon
        if hovered then Theme.fill(ui,x,y,w,h,Theme.WHITE,0.06) end
        arrow(ui,ix,y+(h-size)/2,size,row.open,Theme.MUTED)
        local icon=U.icon(wpn.tpl)
        if icon then ui:drawTextureScaledAspect2(icon,ix+size+pad,y+math.floor(4*k),iconW,h-math.floor(8*k),1,1,1,1) end
        local px,spx=math.floor(28*k),math.floor(22*k)
        local right=x+w-pad
        if wpn.saved>0 then
            local text=getText('IGUI_TWK_SavedCount',wpn.saved)
            right=right-badge(ui,text,right-Theme.textWidth(text,spx,Theme.FONT_BOLD)-spx*0.8,y+(h-Theme.lineHeight(spx,Theme.FONT_BOLD))/2,spx,Theme.NOTICE_OK)-pad
        end
        local top=y+(h-Theme.lineHeight(px,Theme.FONT_BOLD)-Theme.lineHeight(spx))/2
        Theme.text(ui,Theme.fit(wpn.short,right-tx,px,Theme.FONT_BOLD),tx,top,px,Theme.WHITE,1,Theme.FONT_BOLD)
        Theme.text(ui,Theme.fit(wpn.name,right-tx,spx),tx,top+Theme.lineHeight(px,Theme.FONT_BOLD),spx,Theme.MUTED)
        return
    end
    local entry=row.entry
    local selected=self.selected==entry
    if selected then Theme.fill(ui,x,y,w,h,Theme.OWNED_TINT,0.9); Theme.frame(ui,x,y,w,h,Theme.OWNED_BORDER,1,math.max(1,math.floor(2*k)))
    elseif hovered then Theme.fill(ui,x,y,w,h,Theme.WHITE,0.07) end
    local spx,px=math.floor(20*k),math.floor(26*k)
    local tag=getText(entry.kind=='saved' and 'IGUI_TWK_TagSaved' or entry.kind=='preset' and 'IGUI_TWK_TagPreset' or 'IGUI_TWK_TagBase')
    local tagW=badge(ui,tag,tx,y+(h-Theme.lineHeight(spx,Theme.FONT_BOLD))/2,spx,entry.kind=='saved' and Theme.NOTICE_OK or entry.kind=='preset' and Theme.HANDLE or Theme.NAME_FIELD)
    Theme.text(ui,Theme.fit(entry.label,x+w-pad-(tx+tagW+pad),px),tx+tagW+pad,y+(h-Theme.lineHeight(px))/2,px,entry.kind=='preset' and Theme.TEXT or Theme.WHITE)
end
function TWTarkovOpenWindow:pressRow(row)
    if row.kind=='class' then
        self.openClass[row.label]=not row.open
        self:rebuild(true)
    elseif row.kind=='weapon' then
        self.openWeapon[row.key]=not row.open
        self:rebuild(true)
    else
        local now=getTimestampMs()
        local again=self.selected==row.entry and self.clickedAt and now-self.clickedAt<DOUBLE_CLICK
        self.selected=row.entry
        self.clickedAt=now
        self.confirmDelete=nil
        self.error=nil
        if again then self:openSelected() end
    end
end
function TWTarkovOpenWindow:select(key,kind,name)
    for _,class in ipairs(self.classes or {}) do
        for _,weapon in ipairs(class.weapons) do
            for _,entry in ipairs(weapon.entries) do
                if entry.key==key and entry.kind==kind and (name==nil or entry.name==name) then self.selected=entry; return entry end
            end
        end
    end
end
function TWTarkovOpenWindow:openSelected()
    local entry=self.selected
    if not entry then return false end
    local e=self.editor
    if entry.key~=e.planKey then e:setPlanKey(entry.key) end
    local ok=e:loadBase({kind=entry.kind,name=entry.name})
    if ok then self:close() else self.error=e.message or U.reason('unavailable') end
    return ok
end
-- A saved build is deleted on the second press (the button asks to confirm), as Tarkov asks.
function TWTarkovOpenWindow:deleteSelected()
    local entry=self.selected
    if not entry or entry.kind~='saved' then return false end
    if self.confirmDelete~=entry then self.confirmDelete=entry; return false end
    local e=self.editor
    local ok=B.delete(e.player,entry.key,entry.name)
    self.confirmDelete,self.selected=nil,nil
    if ok and entry.key==e.planKey and B.cleanName(e.nameEntry:getText())==entry.name then e.nameEntry:setText('') end
    self:catalog()
    self:rebuild(true)
    return ok
end
function TWTarkovOpenWindow:pasteClipboard()
    self.code:setText(tostring(Clipboard.getClipboard() or ''))
    self.error=nil
    return true
end
function TWTarkovOpenWindow:openCode()
    local text=self.code:getText()
    if text:gsub('%s','')=='' then text=tostring(Clipboard.getClipboard() or ''); self.code:setText(text) end
    local e=self.editor
    local ok=e:pasteCode(text)
    if ok then self:close() else self.error=e.message end
    return ok
end
function TWTarkovOpenWindow:prerender()
    TWTarkovModal.prerender(self)
    local k=self.k
    local a=self.searchIconAt
    Theme.icon(self,'Menu_find',a.x,a.y,a.size,a.size,Theme.MUTED,0.9)
    local px=math.floor(26*k)
    Theme.text(self,getText('IGUI_TWK_CodeLabel'),self.codeLabelAt.x,self.codeLabelAt.y+(self.lineH-Theme.lineHeight(px))/2,px,Theme.TEXT)
    local entry=self.selected
    self.openButton.enabled=entry~=nil
    self.deleteButton.enabled=entry~=nil and entry.kind=='saved'
    self.deleteButton.label=getText(self.confirmDelete and self.confirmDelete==entry and 'IGUI_TWK_DeleteConfirm' or 'IGUI_TWK_DeleteSaved')
    if self.error then self:note(self.error,self.noteY,Theme.WORSE)
    elseif #(self.list.rows)==0 then self:note(getText('IGUI_TWK_SearchNothing'),self.noteY,Theme.MUTED)
    else self:note(getText('IGUI_TWK_OpenHint'),self.noteY,Theme.MUTED) end
end

-- SELECT WEAPON ------------------------------------------------------------------------------------
-- Every weapon of the mod by Tarkov's classes, as cells with their icons (olive: the player has one).
-- Click selects, a double click or SELECT puts it on the builds screen.
TWTarkovWeaponWindow=TWTarkovModal:derive('TWTarkovWeaponWindow')
function TWTarkovWeaponWindow:new(editor)
    return TWTarkovModal.new(self,editor,getText('IGUI_TWK_SelectWeaponTitle'),2300,1700)
end
function TWTarkovWeaponWindow:createContent()
    local k,p,lh,g=self.k,self.pad,self.lineH,self.gap
    local x,y,w=self.boxX+p,self.boxY+self.head+p,self.boxW-2*p
    self.search=textField(self,x+lh+g,y,w-lh-g,lh,getText('IGUI_TWK_SearchName'),function() self:rebuild() end)
    self.searchIconAt={x=x,y=y,size=lh}
    local by=self.boxY+self.boxH-p-lh
    self.cancelButton=button(self,0,by,lh,self.cancel,getText('IGUI_TWK_Cancel'))
    self.cancelButton:setX(self.boxX+self.boxW-p-self.cancelButton.width)
    self.selectButton=button(self,0,by,lh,self.apply,getText('IGUI_TWK_SelectButton'),'accept')
    self.selectButton:setX(self.cancelButton:getX()-g-self.selectButton.width)
    self.list=TWTarkovScrollList:new(x,y+lh+g,w,by-g-(y+lh+g),self)
    self.list:initialise()
    self:addChild(self.list)
    -- Weapons the player carries (the cells of their parts are olive in the part grid too), in the main
    -- inventory or in a bag in it.
    self.owned={}
    R.eachItem(self.editor.player,function(item)
        local key=C.keyForType(item:getFullType())
        if key then self.owned[key]=true end
    end)
    self.weapons={}
    for key,weapon in pairs(D.weapons) do
        local label=Groups.className(Groups.weaponClass(key))
        local short,name=U.weaponShort(key),U.weaponName(key)
        self.weapons[#self.weapons+1]={key=key,tpl=weapon.tpl,short=short,name=name,label=label,text=S.text(short,name,key,label)}
    end
    table.sort(self.weapons,function(a,b) return a.short<b.short end)
    self.selected=self.editor.planKey
    self:rebuild()
    for _,row in ipairs(self.list.rows) do
        for _,cell in ipairs(row.cells or {}) do if cell.key==self.selected then self.list:reveal(row) end end
    end
end
function TWTarkovWeaponWindow:fields() return {self.search} end
function TWTarkovWeaponWindow:rebuild()
    local k=self.k
    local query=S.query(self.search:getText())
    local gap=math.max(2,math.floor(8*k))
    local cellW,cellH=math.floor(250*k),math.floor(132*k)
    local inner=self.list.width-self.list.barW-math.floor(4*k)
    local cols=math.max(1,math.floor((inner+gap)/(cellW+gap)))
    cellW=math.floor((inner+gap)/cols)-gap
    self.cellW,self.cellH,self.cellGap=cellW,cellH,gap
    local rows={}
    local seen={}
    for _,class in ipairs(Groups.CLASS_ORDER) do
        local label=Groups.className(class)
        if not seen[label] then
            seen[label]=true
            local list={}
            for _,weapon in ipairs(self.weapons) do
                if weapon.label==label and S.matches(query,weapon.text) then list[#list+1]=weapon end
            end
            if #list>0 then
                rows[#rows+1]={kind='class',label=label,count=#list,h=math.floor(58*k)}
                for i=1,#list,cols do
                    local cells={}
                    for j=i,math.min(#list,i+cols-1) do cells[#cells+1]={key=list[j].key,weapon=list[j],x=(j-i)*(cellW+gap)} end
                    rows[#rows+1]={kind='cells',cells=cells,h=cellH+gap}
                end
            end
        end
    end
    self.list:setRows(rows)
end
function TWTarkovWeaponWindow:cellAt(row,x)
    for _,cell in ipairs(row.cells or {}) do
        if x>=cell.x and x<cell.x+self.cellW then return cell end
    end
end
function TWTarkovWeaponWindow:drawRow(ui,row,x,y,w,h,hovered)
    local k=self.k
    local pad=math.floor(12*k)
    if row.kind=='class' then
        local px=math.floor(28*k)
        Theme.fill(ui,x,y+math.floor(6*k),w,h-math.floor(12*k),Theme.BUTTON,0.9)
        Theme.text(ui,row.label:upper(),x+pad,y+(h-Theme.lineHeight(px,Theme.FONT_BOLD))/2,px,Theme.WHITE,1,Theme.FONT_BOLD)
        Theme.text(ui,tostring(row.count),x+w-pad,y+(h-Theme.lineHeight(px))/2,px,Theme.MUTED,1,nil,'right')
        return
    end
    local hoverCell=hovered and self:cellAt(row,ui:getMouseX()) or nil
    local cw,ch=self.cellW,self.cellH
    for _,cell in ipairs(row.cells) do
        local cx=x+cell.x
        local owned=self.owned[cell.key]
        Theme.fill(ui,cx,y,cw,ch,owned and Theme.GRID_OWNED or Theme.GRID_CELL,0.97)
        if cell==hoverCell then Theme.fill(ui,cx,y,cw,ch,Theme.WHITE,0.08) end
        local selected=cell.key==self.selected
        Theme.frame(ui,cx,y,cw,ch,selected and Theme.WHITE or cell==hoverCell and Theme.CELL_HOVER or Theme.GRID_BORDER,1,math.max(1,math.floor((selected and 3 or 2)*k)))
        local px=math.max(9,math.floor(24*k))
        Theme.text(ui,Theme.fit(cell.weapon.short,cw-pad*2,px),cx+pad,y+pad*0.5,px,Theme.WHITE)
        local icon=U.icon(cell.weapon.tpl)
        local top=y+Theme.lineHeight(px)+pad*0.5
        if icon then ui:drawTextureScaledAspect2(icon,cx+pad,top,cw-pad*2,y+ch-pad*0.5-top,1,1,1,1) end
    end
end
function TWTarkovWeaponWindow:pressRow(row,x)
    local cell=self:cellAt(row,x)
    if not cell then return end
    local now=getTimestampMs()
    local again=self.selected==cell.key and self.clickedAt and now-self.clickedAt<DOUBLE_CLICK
    self.selected=cell.key
    self.clickedAt=now
    if again then self:apply() end
end
function TWTarkovWeaponWindow:apply()
    local key=self.selected
    if not key or not D.weapons[key] then return false end
    if key~=self.editor.planKey then self.editor:setPlanKey(key) end
    self:close()
    return true
end
function TWTarkovWeaponWindow:prerender()
    TWTarkovModal.prerender(self)
    local a=self.searchIconAt
    Theme.icon(self,'Menu_find',a.x,a.y,a.size,a.size,Theme.MUTED,0.9)
    self.selectButton.enabled=self.selected~=nil
    if #self.list.rows==0 then
        local px=math.floor(26*self.k)
        Theme.text(self,getText('IGUI_TWK_SearchNothing'),self.list:getX()+self.pad,self.list:getY()+self.pad,px,Theme.MUTED)
    end
end

-- PUBLISH ... --------------------------------------------------------------------------------------
-- The build's code (copied to the clipboard at once): the weapon, its parts by Tarkov's template ids
-- and the name; OPEN ... on any computer with the mod opens the identical build from it.
TWTarkovCodeWindow=TWTarkovModal:derive('TWTarkovCodeWindow')
function TWTarkovCodeWindow:new(editor,code,name)
    local o=TWTarkovModal.new(self,editor,getText('IGUI_TWK_PublishTitle'),1600,620)
    o.codeText,o.buildName=code,tostring(name or '')
    return o
end
function TWTarkovCodeWindow:createContent()
    local k,p,lh,g=self.k,self.pad,self.lineH,self.gap
    local x,w=self.boxX+p,self.boxW-2*p
    local px=math.floor(28*k)
    self.infoY=self.boxY+self.head+p
    local y=self.infoY+Theme.lineHeight(px,Theme.FONT_BOLD)+Theme.lineHeight(math.floor(24*k))+g
    self.copyButton=button(self,0,y,lh,self.copy,getText('IGUI_TWK_CopyCode'),'accept')
    self.copyButton:setX(self.boxX+self.boxW-p-self.copyButton.width)
    self.code=textField(self,x,y,self.copyButton:getX()-g-x,lh,'')
    self.code:setText(self.codeText)
    self.noteY=y+lh+g
    local by=self.boxY+self.boxH-p-lh
    self.closeWindow=button(self,0,by,lh,self.cancel,getText('IGUI_TWK_Close'))
    self.closeWindow:setX(self.boxX+self.boxW-p-self.closeWindow.width)
end
function TWTarkovCodeWindow:fields() return {self.code} end
function TWTarkovCodeWindow:copy()
    Clipboard.setClipboard(self.codeText)
    self.code:setText(self.codeText)
    self.copiedAt=getTimestampMs()
    return true
end
function TWTarkovCodeWindow:prerender()
    TWTarkovModal.prerender(self)
    local k=self.k
    local x,w=self.boxX+self.pad,self.boxW-2*self.pad
    local px,spx=math.floor(28*k),math.floor(24*k)
    local e=self.editor
    local title=U.weaponName(e.planKey)..(self.buildName~='' and ' - '..self.buildName or '')
    Theme.text(self,Theme.fit(title,w,px,Theme.FONT_BOLD),x,self.infoY,px,Theme.WHITE,1,Theme.FONT_BOLD)
    local parts=0
    for _ in pairs(e.draft or {}) do parts=parts+1 end
    Theme.text(self,Theme.fit(getText('IGUI_TWK_CodeStats',parts,#self.codeText),w,spx),x,self.infoY+Theme.lineHeight(px,Theme.FONT_BOLD),spx,Theme.MUTED)
    self:note(getText('IGUI_TWK_PublishHint'),self.noteY,self.copiedAt and getTimestampMs()-self.copiedAt<1500 and Theme.NOTICE_OK or Theme.TEXT)
end
