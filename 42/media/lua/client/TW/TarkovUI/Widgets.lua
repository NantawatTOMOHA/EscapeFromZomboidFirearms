-- Small controls of the Tarkov screens: the builds menu button (icon, capital label, cut corner),
-- the filter check box, the big text button (BACK, REVERT) and the square icon button (save, undo,
-- assemble). Each is an ISPanel that draws itself and calls owner[handler](owner, self) on a click.
require('ISUI/ISPanel')
local Theme=require('TW/TarkovUI/Theme')

local function base(class,x,y,w,h,owner,handler)
    local o=ISPanel.new(class,x,y,w,h)
    o.owner,o.handler=owner,handler
    o.background=false
    o.enabled=true
    o.k=owner and owner.k or 1
    return o
end
local function click(self)
    if not self.enabled or not self.handler then return end
    if type(self.handler)=='function' then self.handler(self.owner,self) else self.owner[self.handler](self.owner,self) end
end
local function mouseDown(self) self.pressed=self.enabled; return true end
local function mouseUp(self)
    local was=self.pressed
    self.pressed=false
    if was and self:isMouseOver() then click(self) end
    return true
end
local function mouseUpOutside(self) self.pressed=false end

-- Menu button: dark plate with the right end cut on the bottom corner, icon, CAPITAL label.
TWTarkovMenuButton=ISPanel:derive('TWTarkovMenuButton')
function TWTarkovMenuButton:new(x,y,w,h,owner,handler,label,icon,kind)
    local o=base(self,x,y,w,h,owner,handler)
    o.label,o.iconName,o.kind=label,icon,kind
    return o
end
TWTarkovMenuButton.onMouseDown=mouseDown
TWTarkovMenuButton.onMouseUp=mouseUp
TWTarkovMenuButton.onMouseUpOutside=mouseUpOutside
function TWTarkovMenuButton:prerender()
    local k=self.k
    local w,h=self.width,self.height
    local hover=self.enabled and self:isMouseOver()
    local plate=self.kind=='danger' and Theme.BUTTON_DANGER or not self.enabled and Theme.BUTTON_DISABLED or hover and Theme.BUTTON_HOVER or Theme.BUTTON
    local cut=math.floor(h*0.32)
    Theme.fill(self,0,0,w-cut,h,plate,0.96)
    Theme.quad(self,w-cut,0,w,0,w,h-cut,w-cut,h,plate,0.96)
    Theme.quad(self,w-cut,h-cut,w,h-cut,w,h-cut,w-cut,h,plate,0.96)
    local size=math.floor(h*0.62)
    local ix=math.floor(10*k)
    local iconColor=self.kind=='danger' and Theme.BUTTON_DANGER_ICON or self.enabled and Theme.WHITE or Theme.DIM
    if self.iconName then Theme.icon(self,self.iconName,ix,(h-size)/2,size,size,iconColor,1) end
    local px=math.floor(h*0.52)
    local tx=ix+size+math.floor(12*k)
    Theme.text(self,Theme.fit(self.label,w-tx-cut,px),tx,(h-Theme.lineHeight(px))/2,px,self.enabled and Theme.WHITE or Theme.DIM)
end

-- Filter check box: a square frame with a tick, then the label.
TWTarkovCheck=ISPanel:derive('TWTarkovCheck')
function TWTarkovCheck:new(x,y,owner,handler,label,px,checked)
    local o=base(self,x,y,10,10,owner,handler)
    o.label,o.px,o.checked=label,px,checked~=false
    o:fitSize()
    return o
end
function TWTarkovCheck:fitSize()
    local box=math.floor(self.px*0.78)
    self.box=box
    self:setWidth(math.ceil(box+self.px*0.4+Theme.textWidth(self.label,self.px)))
    self:setHeight(math.ceil(math.max(box,Theme.lineHeight(self.px))))
end
TWTarkovCheck.onMouseDown=mouseDown
TWTarkovCheck.onMouseUpOutside=mouseUpOutside
function TWTarkovCheck:onMouseUp()
    local was=self.pressed
    self.pressed=false
    if was and self:isMouseOver() and self.enabled then
        self.checked=not self.checked
        click(self)
    end
    return true
end
function TWTarkovCheck:prerender()
    local box=self.box
    local y=math.floor((self.height-box)/2)
    local hover=self.enabled and self:isMouseOver()
    Theme.fill(self,0,y,box,box,Theme.BLACK,0.35)
    Theme.frame(self,0,y,box,box,hover and Theme.WHITE or Theme.TEXT,0.9,math.max(1,math.floor(box/14)))
    if self.checked then
        local t=math.max(1.5,box/9)
        Theme.line(self,box*0.24,y+box*0.52,box*0.43,y+box*0.72,t,Theme.TEXT,1)
        Theme.line(self,box*0.43,y+box*0.72,box*0.78,y+box*0.26,t,Theme.TEXT,1)
    end
    Theme.text(self,self.label,box+self.px*0.4,(self.height-Theme.lineHeight(self.px))/2,self.px,self.enabled and Theme.WHITE or Theme.DIM)
end

-- Big thin capital text (BACK at the bottom right, REVERT at the top right of the builds screen).
TWTarkovTextButton=ISPanel:derive('TWTarkovTextButton')
function TWTarkovTextButton:new(x,y,owner,handler,label,px)
    local o=base(self,x,y,10,10,owner,handler)
    o.label,o.px=label,px
    o:setWidth(math.ceil(Theme.textWidth(label,px)+px*0.2))
    o:setHeight(math.ceil(Theme.lineHeight(px)))
    return o
end
TWTarkovTextButton.onMouseDown=mouseDown
TWTarkovTextButton.onMouseUp=mouseUp
TWTarkovTextButton.onMouseUpOutside=mouseUpOutside
function TWTarkovTextButton:prerender()
    local hover=self.enabled and self:isMouseOver()
    Theme.text(self,self.label,self.px*0.1,0,self.px,not self.enabled and Theme.DIM or hover and Theme.WHITE or Theme.TEXT,hover and 1 or 0.92)
    if hover then Theme.fill(self,self.px*0.1,self.height-math.max(1,self.px/24),self.width-self.px*0.2,math.max(1,self.px/24),Theme.WHITE,0.8) end
end

-- Square icon button with its caption shown under it while hovered (the builds screen's save /
-- undo / assemble trio). kind 'assemble' turns olive when the build can be assembled.
TWTarkovIconButton=ISPanel:derive('TWTarkovIconButton')
function TWTarkovIconButton:new(x,y,size,owner,handler,label,icon,kind)
    local o=base(self,x,y,size,size,owner,handler)
    o.label,o.iconName,o.kind=label,icon,kind
    o.ready=false
    return o
end
TWTarkovIconButton.onMouseDown=mouseDown
TWTarkovIconButton.onMouseUp=mouseUp
TWTarkovIconButton.onMouseUpOutside=mouseUpOutside
function TWTarkovIconButton:prerender()
    local s=self.width
    local hover=self.enabled and self:isMouseOver()
    local plate=self.kind=='assemble' and (self.ready and Theme.ASSEMBLE_READY or Theme.ASSEMBLE) or not self.enabled and Theme.BUTTON_DISABLED or hover and Theme.BUTTON_HOVER or Theme.BUTTON
    if hover and self.kind=='assemble' and self.ready then plate=Theme.NOTICE_OK end
    Theme.fill(self,0,0,s,s,plate,0.97)
    local color=not self.enabled and Theme.DIM or self.kind=='assemble' and self.ready and Theme.WHITE or Theme.TEXT
    Theme.icon(self,self.iconName,s*0.18,s*0.18,s*0.64,s*0.64,color,1)
end
function TWTarkovIconButton:render()
    if not (self.enabled and self:isMouseOver()) then return end
    local px=math.floor(self.width*0.26)
    Theme.outlinedText(self,self.label,self.width/2-Theme.textWidth(self.label,px,Theme.FONT_BOLD)/2,self.height+px*0.2,px,Theme.WHITE,1,Theme.FONT_BOLD)
end

-- Window button (OPEN, SELECT, COPY ...): a plate with the capital label in the middle, as Tarkov's
-- DefaultUIButton; kind 'accept' is the window's main action, 'danger' deletes.
TWTarkovPlateButton=ISPanel:derive('TWTarkovPlateButton')
function TWTarkovPlateButton:new(x,y,h,owner,handler,label,kind)
    local o=base(self,x,y,10,h,owner,handler)
    o.label,o.kind=label,kind
    o.px=math.floor(h*0.46)
    o:setWidth(math.ceil(Theme.textWidth(label,o.px,Theme.FONT_BOLD)+h*1.2))
    return o
end
TWTarkovPlateButton.onMouseDown=mouseDown
TWTarkovPlateButton.onMouseUp=mouseUp
TWTarkovPlateButton.onMouseUpOutside=mouseUpOutside
function TWTarkovPlateButton:prerender()
    local w,h=self.width,self.height
    local hover=self.enabled and self:isMouseOver()
    local plate=not self.enabled and Theme.BUTTON_DISABLED or self.kind=='accept' and (hover and Theme.NOTICE_OK or Theme.ASSEMBLE_READY)
        or self.kind=='danger' and (hover and Theme.WORSE or Theme.BUTTON_DANGER) or hover and Theme.BUTTON_HOVER or Theme.BUTTON
    Theme.fill(self,0,0,w,h,plate,0.97)
    Theme.frame(self,0,0,w,h,hover and Theme.CELL_HOVER or Theme.CELL_BORDER,0.9,math.max(1,math.floor(h/24)))
    Theme.text(self,Theme.fit(self.label,w-h*0.4,self.px,Theme.FONT_BOLD),w/2,(h-Theme.lineHeight(self.px,Theme.FONT_BOLD))/2,self.px,
        self.enabled and Theme.WHITE or Theme.DIM,1,Theme.FONT_BOLD,'centre')
end

return {MenuButton=TWTarkovMenuButton,Check=TWTarkovCheck,TextButton=TWTarkovTextButton,IconButton=TWTarkovIconButton,PlateButton=TWTarkovPlateButton}
