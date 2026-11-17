-- Tarkov's INFO pull-out at the left of the modding and builds screens: closed, a small "INFO"
-- tab with a handle; open, the weapon's characteristics (TW/TarkovUI/Stats) as Tarkov's rows - a
-- grey bar, red hatching where the build is worse than the weapon's default preset, blue where it
-- is better, and the change a hovered part would make. The rows are also drawn by the inspect
-- window (TWTarkovStatsPanel.drawRows).
require('ISUI/ISPanel')
local Theme=require('TW/TarkovUI/Theme')

TWTarkovStatsPanel=ISPanel:derive('TWTarkovStatsPanel')

-- One bar row at (x, y), w x h: icon, label, value, bar with reference and preview segments.
function TWTarkovStatsPanel.drawBar(ui,row,x,y,w,h,k)
    local border=math.max(1,math.floor(k*1.6))
    Theme.fill(ui,x,y,w,h,Theme.ROW,Theme.ROW_ALPHA)
    local inner=w-border*2
    local bx,by,bh=x+border,y+border,h-border*2
    local now=math.floor(inner*row.fraction+0.5)
    Theme.gradient(ui,bx,by,now,bh,Theme.BAR_TOP,Theme.BAR_BOTTOM,0.95,5)
    if row.base then
        local base=math.floor(inner*row.base+0.5)
        if row.better then
            Theme.fill(ui,bx+math.min(base,now),by,math.abs(now-base),bh,Theme.BETTER,0.95)
        else
            Theme.hatch(ui,bx+math.min(base,now),by,math.abs(now-base),bh,Theme.HATCH_LIGHT,Theme.HATCH_DARK,0.95)
        end
    end
    if row.previewFraction then
        local after=math.floor(inner*row.previewFraction+0.5)
        local c=row.improved and Theme.BETTER or Theme.WORSE
        Theme.fill(ui,bx+math.min(after,now),by+bh*0.55,math.abs(after-now),bh*0.45,c,1)
        Theme.fill(ui,bx+after-math.max(1,math.floor(k)),by,math.max(2,math.floor(2*k)),bh,Theme.WHITE,0.9)
    end
    Theme.frame(ui,x,y,w,h,Theme.ROW_BORDER,0.9,border)
    local px=math.max(9,math.floor(h*0.5))
    local icon=math.floor(h*0.62)
    local pad=math.floor(h*0.22)
    Theme.icon(ui,row.icon,x+pad,y+(h-icon)/2,icon,icon,Theme.WHITE,0.95)
    local ty=y+(h-Theme.lineHeight(px,Theme.FONT_BOLD))/2
    Theme.outlinedText(ui,row.label,x+pad*2+icon,ty,px,Theme.WHITE,1,Theme.FONT_BOLD)
    local vx=x+w-pad
    Theme.outlinedText(ui,row.text,vx-Theme.textWidth(row.text,px,Theme.FONT_BOLD),ty,px,Theme.WHITE,1,Theme.FONT_BOLD)
    if row.delta then
        local dx=vx-Theme.textWidth(row.text,px,Theme.FONT_BOLD)-pad*0.6-Theme.textWidth(row.delta,px,Theme.FONT_BOLD)
        Theme.outlinedText(ui,row.delta,dx,ty,px,row.improved and Theme.BETTER or Theme.WORSE,1,Theme.FONT_BOLD)
    end
end
-- A text row: darker plate, icon, label, value on the right.
function TWTarkovStatsPanel.drawText(ui,row,x,y,w,h,k)
    Theme.fill(ui,x,y,w,h,Theme.ROW,Theme.ROW_ALPHA*0.85)
    local px=math.max(9,math.floor(h*0.5))
    local icon=math.floor(h*0.56)
    local pad=math.floor(h*0.22)
    Theme.icon(ui,row.icon,x+pad,y+(h-icon)/2,icon,icon,Theme.WHITE,0.9)
    local ty=y+(h-Theme.lineHeight(px,Theme.FONT_BOLD))/2
    Theme.text(ui,row.label,x+pad*2+icon,ty,px,Theme.WHITE,1,Theme.FONT_BOLD)
    local value=Theme.fit(row.text,w*0.5,px)
    Theme.text(ui,value,x+w-pad-Theme.textWidth(value,px),ty,px,Theme.WHITE,1)
    if row.delta then
        Theme.text(ui,row.delta,x+w-pad*1.6-Theme.textWidth(value,px)-Theme.textWidth(row.delta,px,Theme.FONT_BOLD),ty,px,row.improved and Theme.BETTER or Theme.WORSE,1,Theme.FONT_BOLD)
    end
end
-- Rows top-down; returns the bottom. columns: 1 (INFO panel) or 2 (inspect window's text rows).
function TWTarkovStatsPanel.drawRows(ui,bars,texts,x,y,w,rowH,gap,k,textColumns)
    for _,row in ipairs(bars) do
        TWTarkovStatsPanel.drawBar(ui,row,x,y,w,rowH,k)
        y=y+rowH+gap
    end
    local cols=textColumns or 1
    local cw=(w-(cols-1)*gap*2)/cols
    for i,row in ipairs(texts) do
        local c=(i-1)%cols
        TWTarkovStatsPanel.drawText(ui,row,x+c*(cw+gap*2),y,cw,rowH,k)
        if c==cols-1 or i==#texts then y=y+rowH+gap end
    end
    return y
end

-- editor: the screen; it provides statsRows() -> bars, texts.
function TWTarkovStatsPanel:new(editor)
    local o=ISPanel.new(self,0,0,10,10)
    o.editor=editor
    o.k=editor.k
    o.background=false
    o.open=false
    o.progress=0
    o.changedAt=0
    return o
end
-- The tab and the handle are Tarkov's 1080p sprites at twice their size, as on the 4K reference:
-- the tab's plate is 62 x 66 sprite pixels, a handle 23 x 66 with a 3 px dark border.
function TWTarkovStatsPanel:metrics()
    local k=self.k
    return {rowH=math.floor(44*k),gap=math.floor(10*k),width=math.floor(770*k),handle=math.floor(46*k),tabX=math.floor(7*k),tabW=math.floor(124*k),tabH=math.floor(132*k)}
end
-- Left edge of the handle beside the closed tab (its border overlaps the plate).
function TWTarkovStatsPanel:closedHandleX()
    local m=self:metrics()
    return m.tabX+m.tabW-math.floor(6*self.k)
end
-- The closed tab with its handle (screen coordinates): at the left edge, around 60 % of the
-- screen height as in the reference, or below the builds menu where a small screen runs that
-- down past it.
function TWTarkovStatsPanel:tabBox()
    local e,k=self.editor,self.k
    local m=self:metrics()
    local y=math.floor(e.height*0.585)-m.tabH/2
    if e.column then y=math.max(y,e.column.y2+math.floor(16*k)) end
    return {x1=0,y1=y,x2=self:closedHandleX()+m.handle,y2=y+m.tabH}
end
-- Geometry: the panel's own box covers the open rows (left) and the handle; closed, the tab.
function TWTarkovStatsPanel:place()
    local e,k=self.editor,self.k
    local m=self:metrics()
    local bars,texts=e:statsRows()
    self.bars,self.texts=bars,texts
    local rows=#bars+#texts
    local height=rows*(m.rowH+m.gap)
    local sw,sh=e.width,e.height
    self.rowsX=math.floor(30*k)
    self.rowsBottom=sh-math.floor(24*k)
    self.rowsTop=self.rowsBottom-height
    self.tabY=self:tabBox().y1
    -- Closed: only the tab and its handle take the mouse; open (or sliding): the rows too.
    local top,w,h
    if self.open or self:openFraction()>0 then
        top=math.min(self.rowsTop,self.tabY)
        w=self.rowsX+m.width+m.gap+m.handle+math.floor(8*k)
        h=sh-top
    else
        top=self.tabY
        w=self:tabBox().x2
        h=m.tabH
    end
    self:setX(0)
    self:setY(top)
    self:setWidth(w)
    self:setHeight(h)
    self.top=top
end
function TWTarkovStatsPanel:toggle()
    self.open=not self.open
    self.changedAt=getTimestampMs()
end
-- Handle box (panel coordinates): beside the open rows, else beside the closed tab.
function TWTarkovStatsPanel:handleBox()
    local m=self:metrics()
    local f=self:openFraction()
    local closedX=self:closedHandleX()
    local openX=self.rowsX+m.width+m.gap
    local x=closedX+(openX-closedX)*f
    local y=(self.tabY+(self.rowsTop-self.tabY)*f)-self.top
    return {x=x,y=y,w=m.handle,h=m.tabH}
end
function TWTarkovStatsPanel:openFraction()
    local t=math.min(1,(getTimestampMs()-self.changedAt)/160)
    local eased=1-(1-t)*(1-t)
    return self.open and eased or 1-eased
end
function TWTarkovStatsPanel:prerender()
    self:place()
    local k=self.k
    local m=self:metrics()
    local f=self:openFraction()
    local hb=self:handleBox()
    local hover=self:isMouseOver() and self:inHandle(self:getMouseX(),self:getMouseY())
    if f<1 then
        -- Closed tab: Tarkov's plate with list lines and INFO (its soft shadow spills round it).
        local a=1-f
        local tx,ty=m.tabX,self.tabY-self.top
        local s=m.tabW/62
        if not Theme.icon(self,'Info_tab',tx-58*s,ty-67*s,187*s,200*s,Theme.WHITE,a) then
            Theme.fill(self,tx,ty,m.tabW,m.tabH,Theme.ROW,0.85*a)
            local px=math.floor(24*k)
            Theme.outlinedText(self,'INFO',tx+m.tabW*0.30,ty+m.tabH*0.5-Theme.lineHeight(px,Theme.FONT_BOLD)/2,px,Theme.WHITE,a,Theme.FONT_BOLD)
        end
    end
    if f>0 then
        -- Rows slide in from the left edge.
        local x=self.rowsX-(1-f)*(m.width+self.rowsX)
        TWTarkovStatsPanel.drawRows(self,self.bars,self.texts,x,self.rowsTop-self.top,m.width,m.rowH,m.gap,k,1)
    end
    -- Handle: an arrow left while closed, right while open (Tarkov's wms_infowindow_closed / _open).
    if not Theme.icon(self,self.open and 'Info_open' or 'Info_closed',hb.x,hb.y,hb.w,hb.h,Theme.WHITE,1) then
        Theme.fill(self,hb.x,hb.y,hb.w,hb.h,Theme.HANDLE,0.95)
    end
    if hover then Theme.fill(self,hb.x+hb.w*3/23,hb.y+hb.h*3/66,hb.w*17/23,hb.h*60/66,Theme.WHITE,0.25) end
end
function TWTarkovStatsPanel:inHandle(x,y)
    local hb=self:handleBox()
    local m=self:metrics()
    if x>=hb.x and x<=hb.x+hb.w and y>=hb.y and y<=hb.y+hb.h then return true end
    -- The closed tab itself opens the panel too.
    if not self.open then
        local tx,ty=m.tabX,self.tabY-self.top
        return x>=tx and x<=tx+m.tabW and y>=ty and y<=ty+m.tabH
    end
    return false
end
-- Over the rows or the handle (the screen then keeps hover and clicks away from the weapon).
function TWTarkovStatsPanel:covers(x,y)
    if self:inHandle(x,y) then return true end
    if not self.open then return false end
    local m=self:metrics()
    return x>=self.rowsX and x<=self.rowsX+m.width and y>=self.rowsTop-self.top
end
function TWTarkovStatsPanel:onMouseDown(x,y)
    if self:inHandle(x,y) then self:toggle(); return true end
    if self:covers(x,y) then return true end
    return self.editor.layer:onMouseDown(x+self:getX()-self.editor.layer:getX(),y+self:getY()-self.editor.layer:getY())
end
function TWTarkovStatsPanel:onRightMouseDown(x,y)
    if self:covers(x,y) then return true end
    return self.editor.layer:onRightMouseDown(x+self:getX()-self.editor.layer:getX(),y+self:getY()-self.editor.layer:getY())
end
function TWTarkovStatsPanel:onRightMouseUp(x,y)
    if self:covers(x,y) then return true end
    return self.editor.layer:onRightMouseUp(x+self:getX()-self.editor.layer:getX(),y+self:getY()-self.editor.layer:getY())
end
function TWTarkovStatsPanel:onMouseWheel(delta)
    if self:covers(self:getMouseX(),self:getMouseY()) then return true end
    return self.editor.scene:onMouseWheel(delta)
end

return TWTarkovStatsPanel
