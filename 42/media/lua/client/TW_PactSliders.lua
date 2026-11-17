-- NATO / Warsaw Pact sliders of the sandbox options (user 21.10.2026: "a slider with NATO on one side
-- and the Warsaw Pact on the other; moving it shifts the loot towards those countries"). The four
-- options of TW/Data/Loot.pactOption (PactLoot, PactStateLoot, PactStatePeople, PactPeople)
-- are doubles -10..10 (0 half and half, a step 5 of every 100 weapons: TW/Loot.pactShare), which B42
-- draws as text boxes. In the three screens that edit sandbox options -
-- a new game (SandboxOptionsScreen), the host settings (ServerSettingsScreen) and the admin panel in
-- game (ISServerSandboxOptionsUI) - the text box of each becomes a slider under its name, the whole
-- width of the page: NATO at the left end, the Warsaw Pact at the right, under the bar the value and
-- the share of each side. The rows below move down by the extra height.
--
-- The slider keeps the contract the screens read a text box with (getText/setText of the number), so
-- presets, saving and loading stay vanilla. Every step is guarded: a screen this module does not know,
-- or any fault, leaves the vanilla text box.
require 'ISUI/ISPanel'

local S={upgraded=0,faults=0,lastError=''}
TWPactSliders=S

S.STEP=1
S.GAP=6
S.COLORS={nato={r=0.42,g=0.62,b=1.0},pact={r=1.0,g=0.42,b=0.36}}

local function log(text) print('[TW] sandbox sliders: '..text) end

function S.safe(tag,fn,...)
    local ok,err=pcall(fn,...)
    if ok then return err end
    S.faults=S.faults+1
    S.lastError=tag..': '..tostring(err)
    log(S.lastError)
    return nil
end

-- '<namespace>.<id>' -> the option row of TW/Data/Loot (min, max, default) for the four sliders;
-- S.share keeps the scale (pactStep, pactExtreme).
function S.options()
    if S.byName then return S.byName end
    local by={}
    local ok,LD=pcall(require,'TW/Data/Loot')
    if ok and type(LD)=='table' and type(LD.pactOption)=='table' and type(LD.namespace)=='string' then
        local rows={}
        for _,row in ipairs(LD.options or {}) do rows[row.id]=row end
        for _,id in pairs(LD.pactOption) do
            if rows[id] then by[LD.namespace..'.'..id]=rows[id] end
        end
        S.scale={step=LD.pactStep,extreme=LD.pactExtreme}
    end
    S.byName=by
    return by
end

-- The Warsaw Pact share of a value, 0..1 (as TW/Loot.pactShare).
function S.share(v)
    S.options()
    local scale=S.scale
    if not scale then return 0.5 end
    if v<=-scale.extreme then return 0 end
    if v>=scale.extreme then return 1 end
    return math.max(0,math.min(1,0.5+v*scale.step))
end

-- A number as the option parser reads it (Double.parseDouble: ASCII, no thousands separator).
function S.format(v)
    if v==math.floor(v) then return string.format('%d',v) end
    local s=string.format('%.3f',v)
    s=string.gsub(s,'0+$','')
    return (string.gsub(s,'%.$',''))
end

-- A share as whole percent, a tenth where needed ("27.5%"); the sign is added here, translations keep
-- no percent sign.
local function percent(x)
    return S.format(math.floor(x*1000+0.5)/10)..'%'
end

-- The value and the share of each side (UI_TW_PactSliderCaption, UI_TW_PactSliderShares):
-- "-5 - NATO 75% / Warsaw Pact 25%".
function S.caption(v,min,max)
    local num=(v>0 and '+' or '')..S.format(v)
    local p=S.share(v)
    local shares=getText('UI_TW_PactSliderShares',getText('UI_TW_PactSliderNato'),percent(1-p),getText('UI_TW_PactSliderPact'),percent(p))
    return getText('UI_TW_PactSliderCaption',num,shares)
end

-- The control ----------------------------------------------------------------------------------
local Slider=ISPanel:derive('TWPactSlider')
S.Slider=Slider

-- rowH: height of the bar row (the height of the option's label); font: the label's font.
function Slider:new(x,y,width,rowH,font,name,row)
    local capH=getTextManager():getFontHeight(UIFont.Small)+2
    local o=ISPanel:new(x,y,width,rowH+capH)
    setmetatable(o,self)
    self.__index=self
    o.background=false
    o.rowH=rowH
    o.font=font or UIFont.Small
    o.optionName=name
    o.min,o.max,o.default=row.min,row.max,row.default
    o.value=row.default
    o.dragging=false
    -- ISPanelJoypad sends left/right of the gamepad to a focused child with isSlider.
    o.isSlider=true
    return o
end

-- The text box contract of the sandbox screens.
function Slider:setText(text)
    local v=tonumber(text)
    if v==nil or v~=v then v=self.default end
    self:setValue(v)
end
function Slider:getText() return S.format(self.value) end
function Slider:getInternalText() return self:getText() end

function Slider:setValue(v)
    if v<self.min then v=self.min elseif v>self.max then v=self.max end
    self.value=v
end

function Slider:step(dir)
    -- From a fraction (typed in a text box elsewhere) the first step lands on a whole value.
    local v=self.value
    if dir<0 then v=math.ceil(v/S.STEP-1)*S.STEP else v=math.floor(v/S.STEP+1)*S.STEP end
    self:setValue(v)
end

function Slider:geometry()
    local tm=getTextManager()
    local nato,pact=getText('UI_TW_PactSliderNato'),getText('UI_TW_PactSliderPact')
    local x0=tm:MeasureStringX(self.font,nato)+S.GAP
    local x1=self.width-tm:MeasureStringX(self.font,pact)-S.GAP
    local kw=math.max(8,math.floor(self.rowH/3))
    return {nato=nato,pact=pact,x0=x0,x1=x1,kw=kw,span=math.max(1,x1-x0-kw)}
end

function Slider:xOf(v,g)
    return g.x0+g.kw/2+(v-self.min)/(self.max-self.min)*g.span
end

function Slider:dragTo(x)
    local g=self:geometry()
    local v=self.min+(x-g.x0-g.kw/2)/g.span*(self.max-self.min)
    self:setValue(math.floor(v/S.STEP+0.5)*S.STEP)
end

function Slider:onMouseDown(x,y)
    local g=self:geometry()
    if x<g.x0 then self:step(-1)
    elseif x>g.x1 then self:step(1)
    else self.dragging=true;self:dragTo(x) end
    return true
end
function Slider:onMouseMove(dx,dy) if self.dragging then self:dragTo(self:getMouseX()) end end
function Slider:onMouseMoveOutside(dx,dy) if self.dragging then self:dragTo(self:getMouseX()) end end
function Slider:onMouseUp(x,y) self.dragging=false end
function Slider:onMouseUpOutside(x,y) self.dragging=false end

function Slider:onJoypadDirLeft() self:step(-1) end
function Slider:onJoypadDirRight() self:step(1) end
function Slider:setJoypadFocused(focused) self.joypadFocused=focused end

-- The screens move their controls again (the new game screen aligns every control to the right on
-- each page change and resize): the slider keeps the extent from the label column to the right edge
-- of the other controls of its page.
function S.extent(panel,box)
    local left,right=1e9,0
    for _,l in pairs(panel.labels) do if l.parent==box then left=math.min(left,l:getX()) end end
    for _,c in pairs(panel.controls) do
        if c.parent==box and c.Type~='TWPactSlider' then right=math.max(right,c:getX()+c:getWidth()) end
    end
    return left,right
end

function Slider:prerender()
    local panel,box=self.layoutPanel,self.parent
    if not (panel and box) then return end
    local left,right=S.extent(panel,box)
    if right>left then
        if self:getX()~=left then self:setX(left) end
        if self:getWidth()~=right-left then self:setWidth(right-left) end
    end
end

function Slider:render()
    local g=self:geometry()
    local h=self.rowH
    local fh=getTextManager():getFontHeight(self.font)
    local nato,pact=S.COLORS.nato,S.COLORS.pact
    local hover=self.dragging or self:isMouseOver()
    self:drawText(g.nato,0,(h-fh)/2,nato.r,nato.g,nato.b,1,self.font)
    self:drawTextRight(g.pact,self.width,(h-fh)/2,pact.r,pact.g,pact.b,1,self.font)
    local cy=math.floor(h/2)
    -- Track with a tick per step; the middle one (half and half) longer.
    self:drawRect(g.x0,cy-2,g.x1-g.x0,4,1,0.12,0.12,0.12)
    self:drawRectBorder(g.x0,cy-3,g.x1-g.x0,6,1,0.4,0.4,0.4)
    for v=self.min,self.max,S.STEP do
        local x=math.floor(self:xOf(v,g))
        local t=v==0 and 7 or 4
        self:drawRect(x,cy-t,1,t*2,v==0 and 0.9 or 0.45,0.75,0.75,0.75)
    end
    -- From half and half to the value, in the colour of the side it favours.
    local x0,xv=self:xOf(0,g),self:xOf(self.value,g)
    if self.value~=0 then
        local c=self.value<0 and nato or pact
        self:drawRect(math.min(x0,xv),cy-2,math.abs(xv-x0),4,0.9,c.r,c.g,c.b)
    end
    local k=hover and 1 or 0.8
    self:drawRect(xv-g.kw/2,2,g.kw,h-4,1,k,k,k)
    self:drawRectBorder(xv-g.kw/2,2,g.kw,h-4,1,0.25,0.25,0.25)
    self:drawTextCentre(S.caption(self.value,self.min,self.max),(g.x0+g.x1)/2,h+1,0.85,0.85,0.85,1,UIFont.Small)
    if self.joypadFocused then self:drawRectBorder(0,0,self.width,self.height,0.4,0.2,1,1) end
end

-- Screens ----------------------------------------------------------------------------------------
-- The text box of a slider option in a built options page (panel.controls / panel.labels, as the
-- three screens keep them) becomes a slider under its label, from the label column to the right edge
-- of the controls; owner: the screen's own name -> control table, updated too. Returns the count.
function S.upgrade(panel,owner)
    if type(panel)~='table' or type(panel.controls)~='table' or type(panel.labels)~='table' then return 0 end
    local list={}
    for name,row in pairs(S.options()) do
        local old,label=panel.controls[name],panel.labels[name]
        if old and label and old.Type=='ISTextEntryBox' and old.parent then list[#list+1]={name=name,row=row,old=old,label=label} end
    end
    if #list==0 then return 0 end
    table.sort(list,function(a,b) return a.label:getY()<b.label:getY() end)
    local done=0
    for _,e in ipairs(list) do
        local old,label=e.old,e.label
        local box=old.parent
        local left,right=S.extent(panel,box)
        if right>left then
            local top=label:getY()+label:getHeight()+2
            local slider=Slider:new(left,top,right-left,label:getHeight(),label.font,e.name,e.row)
            slider:initialise()
            slider:instantiate()
            slider.tooltip=old.tooltip
            slider.layoutPanel=panel
            slider:setText(old:getText())
            local delta=top-label:getY()+slider:getHeight()-math.max(label:getHeight(),old:getHeight())
            box:removeChild(old)
            for _,c in pairs(box:getChildren()) do
                if c~=box.vscroll and c~=box.hscroll and c:getY()>label:getY() then c:setY(c:getY()+delta) end
            end
            box:addChild(slider)
            box:setScrollHeight(box:getScrollHeight()+delta)
            panel.controls[e.name]=slider
            if type(owner)=='table' then owner[e.name]=slider end
            S.joypad(panel,old,slider)
            done=done+1
        end
    end
    S.upgraded=S.upgraded+done
    return done
end

-- Gamepad: the slider takes the text box's place in the panel's rows, or a row of its own in the
-- order of the page (the admin panel leaves text boxes out of the gamepad rows).
function S.joypad(panel,old,new)
    local lines=panel.joypadButtonsY
    if type(lines)~='table' then return end
    local found=false
    for _,line in ipairs(lines) do
        for i,el in ipairs(line) do if el==old then line[i]=new;found=true end end
    end
    for i,el in ipairs(panel.allJoypadButtons or {}) do if el==old then panel.allJoypadButtons[i]=new end end
    if not found then
        local pos=#lines+1
        for i,line in ipairs(lines) do
            if line[1] and line[1]:getY()>new:getY() then pos=i;break end
        end
        table.insert(lines,pos,{new})
        if panel.allJoypadButtons then table.insert(panel.allJoypadButtons,new) end
    end
    if panel.joypadIndexY and lines[panel.joypadIndexY] then panel.joypadButtons=lines[panel.joypadIndexY] end
end

-- The host settings build every page in ServerSettingsScreen:create (pageEdit, a local class).
function S.upgradeHost(screen)
    local page=screen and screen.pageEdit
    if not (page and page.listbox and page.listbox.items) then return 0 end
    local owner=page.controls and page.controls.Sandbox
    local n=0
    for _,item in ipairs(page.listbox.items) do
        local panel=item.item and item.item.panel
        if panel then n=n+S.upgrade(panel,owner) end
    end
    return n
end

function S.install()
    if S.installed then return end
    S.installed=true
    if SandboxOptionsScreen and SandboxOptionsScreen.createPanel then
        local createPanel=SandboxOptionsScreen.createPanel
        SandboxOptionsScreen.createPanel=function(self,page,...)
            local panel=createPanel(self,page,...)
            S.safe('new game',S.upgrade,panel,self.controls)
            return panel
        end
    end
    if ServerSettingsScreen and ServerSettingsScreen.create then
        local create=ServerSettingsScreen.create
        ServerSettingsScreen.create=function(self,...)
            local result=create(self,...)
            S.safe('host settings',S.upgradeHost,self)
            return result
        end
    end
    if ISServerSandboxOptionsUI and ISServerSandboxOptionsUI.createPanel then
        local createPanel=ISServerSandboxOptionsUI.createPanel
        ISServerSandboxOptionsUI.createPanel=function(self,page,...)
            local panel=createPanel(self,page,...)
            S.safe('admin panel',S.upgrade,panel,self.controls)
            return panel
        end
    end
end

S.install()

return S
