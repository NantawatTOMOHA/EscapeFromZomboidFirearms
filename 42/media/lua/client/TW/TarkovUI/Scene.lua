-- 3D weapon of the Tarkov screens (modding, builds, inspect): the vanilla UI3DScene with a
-- transparent background, the weapon's render copy (or a preview pool copy) and its parts, turned
-- by dragging as in Escape from Tarkov. The camera is mirrored in Lua so that mount points project
-- to the screen in any frame (UI3DScene.calcMatrices: orthographic, 1366 / zoomMult units across
-- the element, zoomMult = exp(zoom * .2) * 160 / 1.82, panned by viewX/viewY).
require('Vehicles/ISUI/ISUI3DScene')
local C=require('TW/Core')
local Render=require('TW/Render')

TWTarkovScene=ISUI3DScene:derive('TWTarkovScene')
function TWTarkovScene:java1(method,a) return self.javaObject:fromLua1(method,a) end
function TWTarkovScene:java2(method,a,b) return self.javaObject:fromLua2(method,a,b) end
function TWTarkovScene:java3(method,a,b,c) return self.javaObject:fromLua3(method,a,b,c) end
function TWTarkovScene:java4(method,a,b,c,d) return self.javaObject:fromLua4(method,a,b,c,d) end
-- owner: the screen or window; owner:sceneFitArea() gives the box the weapon is fitted into
-- ({x1,y1,x2,y2} in the scene's pixels), owner:sceneChanged() is told about every view change.
function TWTarkovScene:new(x,y,width,height,owner)
    local o=ISUI3DScene.new(self,x,y,width,height)
    o.owner=owner
    o.background=false
    o.modelIds={}
    o.offset={0,0,0}
    o.yaw,o.pitch=0,0
    o.zoom,o.maxZoom,o.viewX,o.viewY=3,20,0,0
    o.revision=0
    return o
end
-- The view stays UserDefined: UI3DScene blends a change of the view kind over 350 ms (setView),
-- never a new rotation of the same kind (setViewRotation), so a drag never lags the mirror.
function TWTarkovScene:initialiseScene()
    self.viewName=nil
    self:showView('UserDefined')
    self:java3('setViewRotation',0,0,0)
    self:java1('setMaxZoom',20)
    self:java1('setDrawGrid',false)
    self:java1('setDrawGridAxes',false)
    self:java1('setDrawGridPlane',false)
    self:java1('setDrawAttachments',false)
    self:java1('setGizmoVisible','none')
end
function TWTarkovScene:changed()
    self.revision=self.revision+1
    if self.owner and self.owner.sceneChanged then self.owner:sceneChanged() end
end
function TWTarkovScene:setZoomLevel(zoom)
    self.zoom=math.max(1,math.min(self.maxZoom,math.floor(zoom)))
    self:java1('setZoom',self.zoom)
end
-- Native dragView(dx, dy): viewX -= dx, viewY -= dy, whole pixels; the picture moves by (dx, dy).
function TWTarkovScene:pan(dx,dy)
    dx,dy=math.floor(dx+0.5),math.floor(dy+0.5)
    if dx==0 and dy==0 then return end
    self.viewX,self.viewY=self.viewX-dx,self.viewY-dy
    self:java2('dragView',dx,dy)
end
function TWTarkovScene:viewSize() return math.floor(self.width),math.floor(self.height) end
function TWTarkovScene:pixelsPerUnit()
    return math.exp(self.zoom*0.2)*160/1.82*math.floor(self.width)/1366
end
-- View rotation as JOML rotateXYZ angles; no rotation shows the weapon's right side (the model mirrored, setAssembly).
function TWTarkovScene:viewAngles() return self.pitch,self.yaw,0 end
function TWTarkovScene:showView(name)
    if name~=self.viewName then self.viewName=name end
    self:setView(name)
end
function TWTarkovScene:applyView()
    self:showView('UserDefined')
    self:java3('setViewRotation',self.pitch,self.yaw,0)
    self:changed()
end
-- The live screens show the weapon's own render copy; the builds screen a pool copy configured for
-- the draft. The weapon instance is never touched here.
function TWTarkovScene:setAssembly(key,tree,entries,modelName,keepView)
    for i=#self.modelIds,1,-1 do self:java1('removeModel',self.modelIds[i]) end
    self.modelIds={}
    self:java2('createModel','weapon',modelName)
    self:java2('setModelWeaponRotationHack','weapon',false)
    -- Pistol root models are exported turned about the bore (C.renderRoll): the extra half turn
    -- shows them upright, in the logical root space.
    if C.renderRoll(key) then self:java1('getObjectRotation','weapon'):set(-90.0,180.0,90.0)
    else self:java1('getObjectRotation','weapon'):set(-90.0,0.0,-90.0) end
    -- FB8 item 2 (22.10.2026: "the weapon and its mount points are mirrored in the builder, a module on the left mount
    -- shows on the right one in game, the charging handle on the wrong side"): Tarkov's left-handed models come over
    -- unreflected (model_config.PZ_SOURCE_BASIS), and the game's camera mirrors them back (ModelCamera.Begin scales
    -- X by -1.5; model +X is the weapon's physical left). UI3DScene has no such mirror: the builder drew the left
    -- side as the right. The weapon object mirrors its model X the same way; parts inherit it from their parent.
    self:java1('getObjectScale','weapon'):set(-1.0,1.0,1.0)
    self.modelIds[1]='weapon'
    for index,e in ipairs(entries) do
        local id='part'..index
        -- A folded sight or a sight mode is its own model (TW/GeometryState).
        self:java2('createModel',id,'EscapeFromZomboidFirearms.M_'..e.tpl..(e.state and '_'..e.state or ''))
        self:java2('setModelWeaponRotationHack',id,false)
        self:java4('setObjectParent',id,'origin','weapon',Render.anchorName(e.tpl,e.copy))
        self.modelIds[#self.modelIds+1]=id
    end
    local b=C.bounds(key,tree)
    local same=self.bounds~=nil and self.key==key
    -- A part preview (and the return from it) keeps the model where it is.
    if not (keepView and same) then
        self.offset={-(b.min[2]+b.max[2])/2,-(b.min[3]+b.max[3])/2,(b.min[1]+b.max[1])/2}
    end
    self:java1('getObjectTranslation','weapon'):set(self.offset[1],self.offset[2],self.offset[3])
    self.bounds=b
    if not (keepView and same) and (not same or not self.userView) then self:fit() end
    self.key=key
    self:changed()
end
-- A point of the weapon model (root model space, as the mount frames) in scene pixels. The weapon
-- object mirrors X and is rotated by (-90, 0, -90), which takes the model axes (x, y, z) to the
-- scene axes (y, z, -x), then centred; the view turns the scene (Rx * Ry * Rz) and the orthographic
-- camera scales and pans it. Equal to the native sceneToUIX/Y once the scene has rendered the view.
function TWTarkovScene:project(x,y,z)
    local t=self.offset
    local sx,sy,sz=y+t[1],z+t[2],-x+t[3]
    local ax,ay,az=self:viewAngles()
    if az~=0 then
        local c,s=math.cos(math.rad(az)),math.sin(math.rad(az))
        sx,sy=sx*c-sy*s,sx*s+sy*c
    end
    if ay~=0 then
        local c,s=math.cos(math.rad(ay)),math.sin(math.rad(ay))
        sx,sz=sx*c+sz*s,-sx*s+sz*c
    end
    if ax~=0 then
        local c,s=math.cos(math.rad(ax)),math.sin(math.rad(ax))
        sy,sz=sy*c-sz*s,sy*s+sz*c
    end
    local ppu=self:pixelsPerUnit()
    local w,h=self:viewSize()
    return w/2+sx*ppu-self.viewX,h/2-sy*ppu-self.viewY
end
-- Screen box of a model-space box (optionally placed by a mount matrix).
function TWTarkovScene:projectBox(b,matrix)
    local x1,y1,x2,y2
    for corner=0,7 do
        local p={}
        for a=1,3 do p[a]=math.floor(corner/2^(a-1))%2==0 and b.min[a] or b.max[a] end
        if matrix then p=C.transformPoint(matrix,p) end
        local x,y=self:project(p[1],p[2],p[3])
        x1,y1=math.min(x1 or x,x),math.min(y1 or y,y)
        x2,y2=math.max(x2 or x,x),math.max(y2 or y,y)
    end
    return {x1=x1,y1=y1,x2=x2,y2=y2}
end
-- The whole assembly inside the owner's fit box, at the largest whole zoom step.
function TWTarkovScene:fit()
    if not self.bounds then return end
    local b=self.bounds
    local area=self.owner and self.owner.sceneFitArea and self.owner:sceneFitArea() or {x1=0,y1=0,x2=self.width,y2=self.height}
    local w,h=b.max[2]-b.min[2],b.max[3]-b.min[3]
    local ppu=math.min((area.x2-area.x1)/math.max(w,0.01),(area.y2-area.y1)/math.max(h,0.01))
    self:setZoomLevel(math.log(ppu*1366/math.max(1,self.width)*1.82/160)/0.2)
    self.userView=false
    self:recenter((area.x1+area.x2)/2,(area.y1+area.y2)/2)
end
-- Put the assembly centre (scene origin, at the view centre less the pan) at x, y.
function TWTarkovScene:recenter(x,y)
    local w,h=self:viewSize()
    self:pan(x-(w/2-self.viewX),y-(h/2-self.viewY))
end
function TWTarkovScene:resetView()
    self.yaw,self.pitch=0,0
    self:applyView()
    self:fit()
    self:changed()
end
-- Mouse: the left button turns the weapon, the right button moves it, the wheel zooms towards the
-- cursor. UIElement hands a move only to the topmost child under the cursor, so a drag captures
-- the mouse until the button is released.
function TWTarkovScene:beginDrag(kind)
    self.dragging=kind
    self.dragMoved=0
    self:setCapture(true)
end
function TWTarkovScene:endDrag()
    if not self.dragging then return end
    self.dragging=nil
    self:setCapture(false)
end
function TWTarkovScene:rotateBy(dx,dy)
    self.yaw=self.yaw+dx*0.5
    self.yaw=self.yaw-math.floor(self.yaw/360)*360
    self.pitch=math.max(-80,math.min(80,self.pitch+dy*0.4))
    self:applyView()
end
function TWTarkovScene:onMouseDown(x,y)
    self:beginDrag('turn')
    return true
end
function TWTarkovScene:onRightMouseDown(x,y)
    self:beginDrag('move')
    return true
end
function TWTarkovScene:onMouseMove(dx,dy)
    local kind=self.dragging
    if not kind then return end
    if not isMouseButtonDown(kind=='move' and 1 or 0) then self:endDrag(); return end
    self.dragMoved=(self.dragMoved or 0)+math.abs(dx)+math.abs(dy)
    if kind=='move' then
        self:pan(dx,dy)
        self.userView=true
        self:changed()
    else
        self:rotateBy(dx,dy)
    end
end
function TWTarkovScene:onMouseMoveOutside(dx,dy) self:onMouseMove(dx,dy) end
function TWTarkovScene:onMouseUp()
    if self.dragging=='turn' then self:endDrag() end
    return true
end
function TWTarkovScene:onMouseUpOutside() if self.dragging=='turn' then self:endDrag() end end
function TWTarkovScene:onRightMouseUp()
    if self.dragging=='move' then self:endDrag() end
    return true
end
function TWTarkovScene:onRightMouseUpOutside() if self.dragging=='move' then self:endDrag() end end
-- One zoom step per notch towards the cursor: the scene point under the cursor stays under it.
function TWTarkovScene:onMouseWheel(delta)
    local mx,my=self:getMouseX(),self:getMouseY()
    local w,h=self:viewSize()
    local old=self:pixelsPerUnit()
    local x=(mx-w/2+self.viewX)/old
    local y=(h/2-my-self.viewY)/old
    self:setZoomLevel(self.zoom-delta)
    local new=self:pixelsPerUnit()
    self:pan(self.viewX-(w/2+x*new-mx),self.viewY-(h/2-y*new-my))
    self.userView=true
    self:changed()
    return true
end

return TWTarkovScene
