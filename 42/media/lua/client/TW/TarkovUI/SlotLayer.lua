-- The modding screen's layer over the 3D weapon, as on the Escape from Tarkov modding screen:
-- a leader line from every slot cell to its mount point, the mount points themselves (a white ball
-- for a fitted part, a ring for an empty slot, a blue point with the slot's name for a vital slot)
-- and the slot cells on a ring around the weapon. Recomputed every frame from the mirrored camera,
-- so cells and lines follow turning and zoom. A press on a cell or a point opens the slot's part
-- grid; every other press goes to the 3D scene below (turn, move, zoom).
require('ISUI/ISPanel')
local C=require('TW/Core')
local U=require('TW/UIHelpers')
local Theme=require('TW/TarkovUI/Theme')
local Ring=require('TW/TarkovUI/SlotRing')
local Groups=require('TW/TarkovUI/Groups')
local D=C.data

TWTarkovSlotLayer=ISPanel:derive('TWTarkovSlotLayer')
function TWTarkovSlotLayer:new(x,y,width,height,editor)
    local o=ISPanel.new(self,x,y,width,height)
    o.editor=editor
    o.background=false
    o.cells={}
    o.k=editor.k
    return o
end

-- Where the ring of cells may lie: under the title and filters, above the bottom controls.
function TWTarkovSlotLayer:area()
    local e,k=self.editor,self.k
    return {x1=math.floor(40*k),y1=e.ringTop or math.floor(240*k),x2=self.width-math.floor(40*k),y2=self.height-(e.ringBottomInset or math.floor(150*k))}
end

-- Slots shown by the filters (vital, functional, gear) in the current tree.
function TWTarkovSlotLayer:visibleSlots()
    local e=self.editor
    local list={}
    for _,slot in ipairs(e:viewSlots() or {}) do
        if e:slotShown(slot) then list[#list+1]=slot end
    end
    return list
end

local function hits(cells,rects)
    for _,c in ipairs(cells or {}) do
        for _,r in ipairs(rects) do
            if c.x<r.x2 and r.x1<c.x+c.w and c.y<r.y2 and r.y1<c.y+c.h then return true end
        end
    end
    return false
end

function TWTarkovSlotLayer:compute()
    local e=self.editor
    local scene=e.scene
    self.cells={}
    local tree=e:viewTree()
    if not scene.bounds or not tree then return end
    local anchors,bySlot={},{}
    for _,slot in ipairs(self:visibleSlots()) do
        local m=C.placement(e.key,tree,slot.path)
        if m then
            local ox,oy=scene:getX()-self:getX(),scene:getY()-self:getY()
            local x,y=scene:project(m[1][4],m[2][4],m[3][4])
            local anchor={key=slot.path,x=x+ox,y=y+oy}
            -- A fitted part pulls its cell towards the part itself (a magazine below the weapon,
            -- a sight above it), as on Tarkov's screen; the line still ends at the mount point.
            local bounds=slot.tpl and D.items[slot.tpl] and D.items[slot.tpl].bounds
            if bounds then
                local b=scene:projectBox(bounds,m)
                anchor.wx,anchor.wy=(b.x1+b.x2)/2+ox,(b.y1+b.y2)/2+oy
            end
            anchors[#anchors+1]=anchor
            bySlot[slot.path]=slot
        end
    end
    local box=scene:projectBox(scene.bounds)
    local dx,dy=scene:getX()-self:getX(),scene:getY()-self:getY()
    box={x1=box.x1+dx,y1=box.y1+dy,x2=box.x2+dx,y2=box.y2+dy}
    self.box=box
    local area=self:area()
    local k=self.k
    local cells,ring,size=Ring.arrange(anchors,box,area,math.floor(130*k),math.max(28,math.floor(52*k)))
    -- Cells on the builds menu or the INFO tab (small screens): the ring narrows to the room right
    -- of them, kept centred on the weapon while it still holds the weapon's width.
    local keep=e.keepOut and e:keepOut() or {}
    if hits(cells,keep) then
        local left=area.x1
        for _,r in ipairs(keep) do
            if r.y1<area.y2 and r.y2>area.y1 then left=math.max(left,r.x2+math.floor(16*k)) end
        end
        local mid=(box.x1+box.x2)/2
        local half=math.min(mid-left,area.x2-mid)
        if half*2>=(box.x2-box.x1)+math.max(28,math.floor(52*k))*2 then area={x1=mid-half,y1=area.y1,x2=mid+half,y2=area.y2}
        else area={x1=left,y1=area.y1,x2=area.x2,y2=area.y2} end
        cells,ring,size=Ring.arrange(anchors,box,area,math.floor(130*k),math.max(28,math.floor(52*k)))
    end
    self.ringArea=area
    self.cellSize=size
    self.cells,self.ring=cells,ring
    for _,cell in ipairs(self.cells) do cell.slot=bySlot[cell.key] end
end

function TWTarkovSlotLayer:cellAt(x,y)
    for i=#self.cells,1,-1 do
        local cell=self.cells[i]
        if x>=cell.x and x<cell.x+cell.w and y>=cell.y and y<cell.y+cell.h then return cell end
    end
end
function TWTarkovSlotLayer:pointRadius() return math.max(5,math.floor(13*self.k)) end
-- The mount point under the cursor (a vital point and its label count too).
function TWTarkovSlotLayer:pointAt(x,y)
    local r=self:pointRadius()+math.floor(4*self.k)
    local best,dist
    for _,cell in ipairs(self.cells) do
        local d=(x-cell.ax)^2+(y-cell.ay)^2
        if d<=r*r and (not dist or d<dist) then best,dist=cell,d end
        if cell.labelBox then
            local b=cell.labelBox
            if x>=b.x1 and x<=b.x2 and y>=b.y1 and y<=b.y2 and not best then best=cell end
        end
    end
    return best
end
-- The fitted part under a point of the model itself: the smallest projected box that holds it.
function TWTarkovSlotLayer:partAt(x,y)
    local e=self.editor
    local tree=e:viewTree()
    if not tree then return end
    local dx,dy=e.scene:getX()-self:getX(),e.scene:getY()-self:getY()
    local best,area
    for _,cell in ipairs(self.cells) do
        local slot=cell.slot
        local item=slot.tpl and not slot.preview and D.items[slot.tpl]
        local placement=item and item.bounds and C.placement(e.key,tree,cell.key)
        if placement then
            local b=e.scene:projectBox(item.bounds,placement)
            if x>=b.x1+dx and x<=b.x2+dx and y>=b.y1+dy and y<=b.y2+dy then
                local a=(b.x2-b.x1)*(b.y2-b.y1)
                if not area or a<area or a==area and #cell.key>#best.key then best,area=cell,a end
            end
        end
    end
    return best
end
function TWTarkovSlotLayer:targetAt(x,y)
    return self:cellAt(x,y) or self:pointAt(x,y)
end

-- Cell look by state: open (its grid is shown), hovered, required and empty, previewed part,
-- builds marks (blue: kept or available, amber: only in a bag, red: missing), fitted, empty.
function TWTarkovSlotLayer:drawCell(cell,hovered)
    local e,k=self.editor,self.k
    local slot=cell.slot
    local x,y,w,h=cell.x,cell.y,cell.w,cell.h
    local open=e.grid:getIsVisible() and e.grid.path==slot.path
    local required=slot.definition.required and not slot.tpl
    local fill,alpha,border=Theme.CELL,Theme.CELL_ALPHA,Theme.CELL_BORDER
    if not slot.tpl then fill,alpha=Theme.CELL_EMPTY,0.78 end
    if slot.mark=='kept' or slot.mark=='owned' then fill,alpha,border=Theme.OWNED_TINT,0.72,Theme.OWNED_BORDER
    elseif slot.mark=='bag' then border=Theme.BAG_BORDER
    elseif slot.mark=='missing' then fill,alpha,border=Theme.MISSING_TINT,0.78,Theme.MISSING_BORDER end
    if required then border=Theme.MISSING_BORDER end
    if slot.preview then border=Theme.BAG_BORDER end
    if open or hovered then border=Theme.CELL_HOVER end
    Theme.fill(self,x,y,w,h,fill,alpha)
    if hovered or open then Theme.fill(self,x,y,w,h,Theme.WHITE,0.06) end
    Theme.frame(self,x,y,w,h,border,1,math.max(1,math.floor(2*k)))
    -- Kinds of parts the slot takes, above the cell's corner (Tarkov's icon_mod_* glyphs).
    local glyph=math.floor(20*k)
    Groups.drawSlotGlyphs(self,slot,x+math.floor(2*k),y-glyph-math.floor(4*k),glyph,Theme.TEXT,0.9)
    local px=math.max(9,math.floor(22*k))
    local pad=math.floor(6*k)
    local textTop=y+pad*0.5
    local lines
    if slot.tpl then lines=Theme.wrap(U.shortName(slot.tpl),w-pad*2,px,2)
    else lines={Theme.fit(getText('IGUI_TWK_Empty'),w-pad*2,px)} end
    local iconTop=y+pad
    for i,line in ipairs(lines) do
        Theme.text(self,line,x+pad,textTop+(i-1)*Theme.lineHeight(px)*0.86,px,slot.tpl and Theme.WHITE or Theme.MUTED)
    end
    if slot.tpl then
        local icon=U.icon(slot.tpl)
        local top=y+Theme.lineHeight(px)*0.95
        if icon then self:drawTextureScaledAspect2(icon,x+pad,top,w-pad*2,y+h-pad-top,1,1,1,1) end
        Groups.badge(self,slot.tpl,x+math.floor(3*k),y+h-math.floor(29*k),math.floor(26*k))
        -- Rounds of the inserted magazine, as Tarkov's 26/30.
        local rounds=e:roundsText(slot)
        if rounds then Theme.outlinedText(self,rounds,x+w-pad-Theme.textWidth(rounds,px),y+h-Theme.lineHeight(px)-pad*0.3,px,Theme.WHITE,1) end
    end
end

function TWTarkovSlotLayer:prerender()
    local e,k=self.editor,self.k
    self:compute()
    local mx,my=self:getMouseX(),self:getMouseY()
    local over=self:isMouseOver() and not e:pointerOverPanels()
    self.hover=over and not e.scene.dragging and self:targetAt(mx,my) or nil
    self.hoverPart=(over and not self.hover and not e.scene.dragging) and self:partAt(mx,my) or nil
    local highlight=self.hover or self.hoverPart
    local width=math.max(1,1.2*k)
    -- Leader lines in the class colour (white functional, green gear, blue vital), the
    -- highlighted one on top.
    for _,cell in ipairs(self.cells) do
        cell.class=Groups.slotClass(cell.slot)
        if cell~=highlight then Theme.line(self,cell.lx,cell.ly,cell.ax,cell.ay,width,Groups.LINE[cell.class],0.55) end
    end
    if highlight then Theme.line(self,highlight.lx,highlight.ly,highlight.ax,highlight.ay,width*1.8,Groups.LINE[highlight.class],1) end
    -- Mount points; Gear and Master points carry the slot's name (ZATVOR, PRIKLAD, MAGAZIN ...).
    local size=math.max(10,math.floor(26*k))
    local px=math.max(9,math.floor(21*k))
    local lh=Theme.lineHeight(px,Theme.FONT_BOLD)
    local placed={}
    for _,cell in ipairs(self.cells) do
        local slot=cell.slot
        local a=(cell==highlight) and 1 or 0.95
        cell.labelBox=nil
        Groups.point(self,cell.class,slot.tpl~=nil and not slot.preview,cell.ax,cell.ay,size,a)
        if cell.class~='functional' then
            local label=Groups.slotLabel(slot.path,e.key)
            local lw=Theme.textWidth(label,px,Theme.FONT_BOLD)
            -- Right of the point; a label that would cover another one tries the left side, then a
            -- line lower, a line higher, two lines lower ...; with no free place, the least covered.
            local box,cover
            for attempt=0,13 do
                local right=attempt%2==0
                local step=math.floor(attempt/2)
                local shift=(step%2==1 and 1 or -1)*math.floor((step+1)/2)
                local lx=right and cell.ax+size*0.62 or cell.ax-size*0.62-lw
                local ly=cell.ay-lh*0.5+shift*lh*0.9
                local b={x1=lx-math.floor(4*k),y1=ly+lh*0.08,x2=lx+lw+math.floor(4*k),y2=ly+lh*0.92}
                local c=0
                for _,o in ipairs(placed) do
                    local w,h=math.min(b.x2,o.x2)-math.max(b.x1,o.x1),math.min(b.y2,o.y2)-math.max(b.y1,o.y1)
                    if w>0 and h>0 then c=c+w*h end
                end
                if not box or c<cover then box,cover=b,c end
                if c==0 then break end
            end
            placed[#placed+1]=box
            local lx,ly=box.x1+math.floor(4*k),box.y1-lh*0.08
            Theme.fill(self,lx-math.floor(2*k),ly+math.floor(2*k),lw+math.floor(4*k),lh-math.floor(3*k),Theme.BLACK,0.45*a)
            Theme.outlinedText(self,label,lx,ly,px,Theme.WHITE,a,Theme.FONT_BOLD)
            cell.labelBox={x1=lx,y1=ly,x2=lx+lw,y2=ly+lh}
        end
        if cell==highlight then Theme.ring(self,cell.ax,cell.ay,size*0.85,math.max(1.5,2*k),Groups.LINE[cell.class],0.9) end
    end
    -- The hovered part itself, marked on the model in its class colour (Tarkov highlights it).
    local tpl=highlight and highlight.slot.tpl
    local bounds=tpl and D.items[tpl] and D.items[tpl].bounds
    local placement=bounds and C.placement(e.key,e:viewTree(),highlight.key)
    if placement then
        local b=e.scene:projectBox(bounds,placement)
        local dx,dy=e.scene:getX()-self:getX(),e.scene:getY()-self:getY()
        local pad=math.floor(4*k)
        Theme.corners(self,b.x1+dx-pad,b.y1+dy-pad,b.x2+dx+pad,b.y2+dy+pad,math.floor(14*k),math.max(1,math.floor(2*k)),Groups.LINE[highlight.class],0.9)
    end
    for _,cell in ipairs(self.cells) do self:drawCell(cell,cell==highlight) end
    -- Tarkov's drop-down handle at the right of the cell whose grid is open: down for a grid
    -- below the cell, up for one above it (its 1080p sprite at twice the size, as on the 4K
    -- reference, fitted to the cell's height).
    local grid=e.grid
    if grid:getIsVisible() and grid.path then
        for _,cell in ipairs(self.cells) do
            if cell.key==grid.path then
                local h=cell.h*0.94
                local w=h*23/66
                Theme.icon(self,grid:getY()>cell.y and 'Dropdown_open' or 'Dropdown_close',cell.x+cell.w-w*3/23,cell.y+(cell.h-h)/2,w,h,Theme.WHITE,1)
            end
        end
    end
end

-- Presses outside the cells and points turn or move the weapon (the scene captures the mouse).
function TWTarkovSlotLayer:onMouseDown(x,y)
    local e=self.editor
    local cell=self:targetAt(x,y)
    if cell and cell.slot.preview then return true end
    if cell then
        if e.grid:getIsVisible() and e.grid.path==cell.key then e.grid:close(); return true end
        e:openGrid(cell)
        return true
    end
    if e.grid:getIsVisible() then e.grid:close() end
    return e.scene:onMouseDown(x,y)
end
function TWTarkovSlotLayer:onRightMouseDown(x,y)
    local cell=self:targetAt(x,y) or self:partAt(x,y)
    if cell then self.rightCell=cell; return true end
    return self.editor.scene:onRightMouseDown(x,y)
end
function TWTarkovSlotLayer:onRightMouseUp(x,y)
    local cell=self.rightCell
    self.rightCell=nil
    if cell and not cell.slot.preview then self.editor:cellMenu(cell); return true end
    return self.editor.scene:onRightMouseUp(x,y)
end
function TWTarkovSlotLayer:onMouseWheel(delta) return self.editor.scene:onMouseWheel(delta) end
