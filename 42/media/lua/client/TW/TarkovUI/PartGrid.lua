-- The slot's drop-down of parts, as on Tarkov's builds screen (reference Screenshot_330/332): an
-- EMPTY cell alone in the first row (takes the part off), then the parts that fit, in cells -
-- olive for parts the player has, dark for the others, a red strip for a part the rules refuse.
-- Hovering a cell puts the part on the 3D model (after a short rest) and shows what it changes;
-- a click installs it (modding: timed actions) or puts it in the build (builds). The first row
-- also has the search by name (user's report of 21.10.2026, item 5): right of EMPTY, or alone.
require('ISUI/ISPanel')
require('ISUI/ISTextEntryBox')
local U=require('TW/UIHelpers')
local Theme=require('TW/TarkovUI/Theme')
local Groups=require('TW/TarkovUI/Groups')
local S=require('TW/TarkovUI/Search')

TWTarkovPartGrid=ISPanel:derive('TWTarkovPartGrid')
function TWTarkovPartGrid:new(editor)
    local o=ISPanel.new(self,0,0,10,10)
    o.editor=editor
    o.k=editor.k
    o.background=false
    o.scroll=0
    return o
end
function TWTarkovPartGrid:createChildren()
    ISPanel.createChildren(self)
    local box=ISTextEntryBox:new('',0,0,10,10)
    box.font=UIFont.Medium
    box:initialise()
    box.backgroundColor={r=0,g=0,b=0,a=0.75}
    box.borderColor={r=Theme.NAME_FIELD.r,g=Theme.NAME_FIELD.g,b=Theme.NAME_FIELD.b,a=0.9}
    box:setPlaceholderText(getText('IGUI_TWK_SearchParts'))
    box.onTextChangeFunction=TWTarkovPartGrid.onSearch
    box.target=self
    self:addChild(box)
    self.search=box
end
function TWTarkovPartGrid:cellSize() return self.size or math.floor(128*self.k) end
-- The names a part is found by: Tarkov's short name and the item's full name.
local searchTexts={}
local function searchText(tpl)
    local text=searchTexts[tpl]
    if not text then
        text=S.text(U.shortName(tpl),U.partName(tpl))
        searchTexts[tpl]=text
    end
    return text
end
-- Entries: the candidate rows of the slot that match the search (EMPTY is a cell of its own).
function TWTarkovPartGrid:entries()
    local e=self.editor
    local list={}
    for _,row in ipairs(e.candidates or {}) do
        if S.matches(self.query,searchText(row.tpl)) then list[#list+1]=row end
    end
    return list
end
function TWTarkovPartGrid:onSearch()
    local query=S.query(self.search:getText())
    self.query=query
    self.scroll=0
    if self:getIsVisible() then self:place() end
end
function TWTarkovPartGrid:hasEmpty()
    local e=self.editor
    return self.path~=nil and e.tree[self.path]~=nil
end
-- The first row: EMPTY and the search right of it, or the search alone (a lower row).
function TWTarkovPartGrid:headHeight()
    return self:hasEmpty() and self.size or self.searchH+math.floor(8*self.k)
end
-- Below the slot's cell when there is room, else above; columns as the screen allows (7 at most).
-- The side and the width follow all the slot's parts; while the search narrows them the grid keeps
-- its edge at the cell and only its height follows the found parts.
function TWTarkovPartGrid:place()
    local e,k=self.editor,self.k
    local a=self.anchor
    local size=a.w
    self.size=size
    local gap=math.max(2,math.floor(6*k))
    self.gap=gap
    local fh=getTextManager():getFontHeight(UIFont.Medium)
    local font=fh+8<=math.floor(size*0.5) and UIFont.Medium or UIFont.Small
    if self.search.font~=font then self.search:setFont(font) end
    self.searchH=math.max(getTextManager():getFontHeight(font)+8,math.floor(size*0.42))
    local all=#(e.candidates or {})
    local count=#self:entries()
    local cols=math.max(1,math.min(7,math.max(4,math.ceil(math.sqrt(math.max(1,all))*1.3)),all))
    local maxCols=math.max(1,math.floor((e.width-math.floor(40*k)+gap)/(size+gap)))
    cols=math.min(cols,maxCols)
    -- The search needs room beside EMPTY: at least two more columns.
    if all>0 then cols=math.max(cols,math.min(maxCols,3)) end
    self.cols=cols
    local rows=math.ceil(count/cols)
    local head=self:headHeight()
    local below=e.height-math.floor(20*k)-(a.y+a.h+gap)
    local above=a.y-gap-math.floor(20*k)
    local room=math.max(below,above)-head-gap
    local maxRows=math.max(1,math.min(5,math.ceil(all/cols),math.floor((room+gap)/(size+gap))))
    self.rows=rows
    local width=cols*(size+gap)-gap+math.floor(14*k)
    local full=head+gap+maxRows*(size+gap)-gap
    if all==0 then full=head+gap+math.floor(size*0.6) end
    -- The side is chosen for all the parts; the found ones take as many rows as they need.
    self.maxRows=math.max(1,math.min(maxRows,rows))
    local height=all==0 and full or head+gap+self.maxRows*(size+gap)-gap
    local y
    if below>=full then y=a.y+a.h+gap
    elseif below>=above then y=e.height-math.floor(20*k)-full
    else y=a.y-gap-height end
    local x=math.max(math.floor(20*k),math.min(e.width-math.floor(20*k)-width,a.x))
    self:setX(math.floor(x));self:setY(math.floor(math.max(math.floor(20*k),y)))
    self:setWidth(width);self:setHeight(height)
    self.count=all
    self.scroll=math.max(0,math.min(self.scroll,rows-self.maxRows))
    -- The search field: right of EMPTY (and the magnifier), or along the top.
    local icon=math.floor(self.searchH*0.8)
    local sx=(self:hasEmpty() and size+gap or 0)+icon+math.floor(10*k)
    self.searchIcon={x=sx-icon-math.floor(6*k),y=math.floor((head-icon)/2),size=icon}
    self.search:setX(sx)
    self.search:setY(math.floor((head-self.searchH)/2))
    self.search:setWidth(math.max(math.floor(60*k),width-math.floor(14*k)-sx))
    self.search:setHeight(self.searchH)
end
function TWTarkovPartGrid:open(path,cell)
    if self.path~=path or not self:getIsVisible() then
        self.search:setText('')
        self.query=nil
    end
    self.path=path
    self.scroll=0
    self.anchor={x=cell.x,y=cell.y,w=cell.w,h=cell.h}
    self:place()
    self:setVisible(true)
    self:bringToTop()
end
function TWTarkovPartGrid:close()
    if self.search and self.search:isFocused() then self.search:unfocus() end
    if not self:getIsVisible() then return end
    self:setVisible(false)
    self.editor:endPreview()
    self.path=nil
    self.hoverEntry=nil
end
-- Cell boxes in grid coordinates: {entry, x, y, w, h}; EMPTY first, then the parts under the head.
function TWTarkovPartGrid:cells()
    local list={}
    local size,gap=self.size,self.gap
    if self:hasEmpty() then list[1]={remove=true,x=0,y=0,w=size,h=size} end
    local top=self:headHeight()+gap
    local entries=self:entries()
    for i=1,self.maxRows*self.cols do
        local index=self.scroll*self.cols+i
        local entry=entries[index]
        if not entry then break end
        local c=(i-1)%self.cols
        local r=math.floor((i-1)/self.cols)
        list[#list+1]={entry=entry,x=c*(size+gap),y=top+r*(size+gap),w=size,h=size}
    end
    return list
end
function TWTarkovPartGrid:cellAt(x,y)
    for _,cell in ipairs(self:cells()) do
        if x>=cell.x and x<cell.x+cell.w and y>=cell.y and y<cell.y+cell.h then return cell end
    end
end
function TWTarkovPartGrid:drawEntry(cell,hovered)
    local e,k=self.editor,self.k
    local x,y,w,h=cell.x,cell.y,cell.w,cell.h
    local pad=math.floor(6*k)
    local px=math.max(9,math.floor(22*k))
    if cell.remove then
        Theme.fill(self,x,y,w,h,Theme.CELL_EMPTY,0.97)
        Theme.frame(self,x,y,w,h,hovered and Theme.CELL_HOVER or Theme.GRID_BORDER,1,math.max(1,math.floor(2*k)))
        Theme.text(self,Theme.fit(getText('IGUI_TWK_Empty'),w-pad*2,px),x+pad,y+pad*0.5,px,e.removeAllowed and Theme.WHITE or Theme.DIM)
        return
    end
    local row=cell.entry
    local owned=row.main>0 or row.nested>0
    local fill=owned and row.allowed and Theme.GRID_OWNED or Theme.GRID_CELL
    Theme.fill(self,x,y,w,h,fill,0.97)
    if hovered then Theme.fill(self,x,y,w,h,Theme.WHITE,0.08) end
    local border=row.installed and Theme.WHITE or hovered and Theme.CELL_HOVER or Theme.GRID_BORDER
    Theme.frame(self,x,y,w,h,border,1,math.max(1,math.floor(2*k)))
    local strip=math.floor(30*k)
    local lines=Theme.wrap(U.shortName(row.tpl),w-pad*2,px,2)
    for i,line in ipairs(lines) do Theme.text(self,line,x+pad,y+pad*0.5+(i-1)*Theme.lineHeight(px)*0.86,px,Theme.WHITE) end
    local icon=U.icon(row.tpl)
    local top=y+Theme.lineHeight(px)*0.95
    if icon then self:drawTextureScaledAspect2(icon,x+pad,top,w-pad*2,y+h-strip-top-pad*0.5,row.allowed and 1 or 0.55,1,1,1) end
    -- Bottom strip: group badge and the count the player has (Tarkov shows the price here).
    local refused=not row.allowed and row.reason~='notInInventory' and not row.installed
    Theme.fill(self,x+math.floor(2*k),y+h-strip,w-math.floor(4*k),strip-math.floor(2*k),refused and Theme.GRID_REFUSED or Theme.GRID_STRIP,0.92)
    Groups.badge(self,row.tpl,x+math.floor(4*k),y+h-strip+math.floor(2*k),strip-math.floor(6*k))
    -- The fitted part is marked by its white frame; the others show how many the player has.
    local label=''
    if row.installed then label=''
    elseif owned then label='x'..tostring(row.main+row.nested)
    else label=getText('IGUI_TWK_NotOwned') end
    local spx=math.max(8,math.floor(20*k))
    Theme.text(self,Theme.fit(label,w-strip-pad,spx),x+strip+pad*0.3,y+h-strip+(strip-Theme.lineHeight(spx))/2,spx,
        refused and Theme.WHITE or owned and Theme.NOTICE_OK or Theme.MUTED)
end
function TWTarkovPartGrid:prerender()
    local e,k=self.editor,self.k
    if e.selectedPath~=self.path then self:close(); return end
    if #(e.candidates or {})~=self.count then self:place() end
    local mx,my=self:getMouseX(),self:getMouseY()
    local hovered=self:isMouseOver() and self:cellAt(mx,my) or nil
    self.hoverCell=hovered
    -- Near-black plate behind the cells (Tarkov's drop-down hides what lies under it).
    Theme.fill(self,-math.floor(4*k),-math.floor(4*k),self.width+math.floor(8*k),self.height+math.floor(8*k),Theme.BLACK,0.92)
    local si=self.searchIcon
    Theme.icon(self,'Menu_find',si.x,si.y,si.size,si.size,self.query and Theme.WHITE or Theme.MUTED,0.9)
    for _,cell in ipairs(self:cells()) do self:drawEntry(cell,cell==hovered) end
    if #self:entries()==0 then
        local px=math.max(9,math.floor(22*k))
        local y=self:headHeight()+self.gap
        local text=#(e.candidates or {})>0 and 'IGUI_TWK_SearchNothing' or e.mode=='plan' and 'IGUI_TWK_NoParts' or 'IGUI_TWK_NoPartsOwned'
        Theme.text(self,getText(text),math.floor(8*k),y+math.floor(8*k),px,Theme.MUTED)
    end
    -- Scroll bar while rows are hidden.
    if self.rows>self.maxRows then
        local top=self:headHeight()+self.gap
        local track=self.height-top
        local bar=math.max(math.floor(20*k),track*self.maxRows/self.rows)
        local pos=self.scroll/(self.rows-self.maxRows)*(track-bar)
        Theme.fill(self,self.width-math.floor(8*k),top+pos,math.floor(5*k),bar,Theme.HANDLE,0.9)
    end
end
function TWTarkovPartGrid:onMouseMove(dx,dy)
    local cell=self:cellAt(self:getMouseX(),self:getMouseY())
    local e=self.editor
    if cell and cell.entry then
        if cell.entry~=e.candidate or e.previewKey~=cell.entry.key and TWAssemblyWindow.previewable(cell.entry) then e:previewCandidate(cell.entry) end
    end
end
function TWTarkovPartGrid:onMouseDown(x,y)
    local cell=self:cellAt(x,y)
    if cell then self.pressed=cell end
    return true
end
function TWTarkovPartGrid:onMouseUp(x,y)
    local cell=self:cellAt(x,y)
    local pressed=self.pressed
    self.pressed=nil
    if cell and pressed and (cell.remove and pressed.remove or cell.entry and cell.entry==pressed.entry) then
        self.editor:chooseFromGrid(cell.remove and {remove=true} or cell.entry)
    end
    return true
end
function TWTarkovPartGrid:onMouseDownOutside(x,y)
    if not self:getIsVisible() then return end
    -- FB8 item 3 (22.10.2026, "the search can't be clicked - the parts window just closes"): a press consumed by a
    -- child (the search field takes focus and consumes it) reaches the grid as onMouseDownOutside (UIElement.onMouseDown),
    -- with grid-local or parent coordinates; a press inside the grid never closes it.
    local mx,my=self:getMouseX(),self:getMouseY()
    if mx>=0 and my>=0 and mx<self.width and my<self.height then return end
    local e=self.editor
    local layer=e.layer
    -- A press on the slot's own cell or point toggles the grid there (TWTarkovSlotLayer:onMouseDown).
    local cell=layer:targetAt(layer:getMouseX(),layer:getMouseY())
    if cell and cell.key==self.path then return end
    self:close()
end
function TWTarkovPartGrid:onMouseWheel(delta)
    self.scroll=math.max(0,math.min(self.rows-self.maxRows,self.scroll+delta))
    return true
end

return TWTarkovPartGrid
