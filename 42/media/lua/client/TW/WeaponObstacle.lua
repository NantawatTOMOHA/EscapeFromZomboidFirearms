-- Local geometric query only: no bone writes, shot interception or foreign
-- implementation. The future pose layer consumes state() after blending.
require 'PZAPI/ModOptions'
local C=require('TW/Core')
local R=require('TW/Runtime')
local Length=require('TW/WeaponLength')
local D=require('TW/Data/WeaponObstacle')
local O={players=setmetatable({},{__mode='k'}),config=D}
TWObstacle=O
local G=D.geometry
local abs,min,max,floor=math.abs,math.min,math.max,math.floor
local options
local reported={}

local function logOnce(reason)
    if reported[reason] then return end
    reported[reason]=true
    print('[TW-OBSTACLE] unavailable: '..reason)
end

function O.bindOptions()
    local api=PZAPI and PZAPI.ModOptions
    options=api and api.getOptions and api:getOptions('EscapeFromZomboidFirearms')
    if not options then return false end
    if not options:getOption('WeaponObstacleIsometric') then
        options:addTickBox('WeaponObstacleIsometric',getText('UI_TW_WeaponObstacleIsometric'),false,
            getText('UI_TW_WeaponObstacleIsometric_tooltip'))
    end
    return true
end

function O.enabled()
    if not options then O.bindOptions() end
    local vp=rawget(_G,'Viewpoint')
    local view=vp and vp.View
    if view and view.next then
        local ok,nextView=pcall(view.next)
        if not ok or (nextView~='first' and nextView~='third' and nextView~='isometric') then
            logOnce('Viewpoint.View.next')
            return false
        end
        -- next() reports the NEXT view: third/isometric mean current FP/TP.
        if nextView~='first' then
            local policy=rawget(_G,'TWViewpoint')
            if not policy or not policy.enabled then return false end
            local ready,value=pcall(policy.enabled)
            return ready and value==true
        end
    end
    local option=options and options:getOption('WeaponObstacleIsometric')
    return option~=nil and option:getValue()==true
end

local function flag(properties,name)
    local value=IsoFlagType and IsoFlagType[name]
    return properties~=nil and value~=nil and properties:has(value)
end

local function box(out,x1,y1,z1,x2,y2,z2,kind,object)
    out[#out+1]={kind=kind,object=object,planes={
        {1,0,0,x2},{-1,0,0,-x1},{0,1,0,y2},{0,-1,0,-y1},
        {0,0,1,z2},{0,0,-1,-z1}}}
end

-- Heights here are fractions of a PZ level, converted once into tile units.
local function edge(out,x,y,z,side,u1,u2,bottom,top,kind,object)
    if side=='S' then y=y+1;side='N' elseif side=='E' then x=x+1;side='W' end
    local h=G.edgeHalfWidth
    local z1,z2=(z+bottom)*D.levelHeight,(z+top)*D.levelHeight
    if side=='N' then box(out,x+u1,y-h,z1,x+u2,y+h,z2,kind,object)
    elseif side=='W' then box(out,x-h,y+u1,z1,x+h,y+u2,z2,kind,object) end
end

local function opening(out,x,y,z,side,kind,object)
    local jamb=G.jambWidth
    local bottom=kind=='window' and G.windowBottom or 0
    local top=kind=='window' and G.windowTop or G.doorTop
    edge(out,x,y,z,side,0,jamb,0,1,kind..'-frame',object)
    edge(out,x,y,z,side,1-jamb,1,0,1,kind..'-frame',object)
    if bottom>0 then edge(out,x,y,z,side,jamb,1-jamb,0,bottom,kind..'-frame',object) end
    edge(out,x,y,z,side,jamb,1-jamb,top,1,kind..'-frame',object)
end

local function aperture(object,properties)
    if instanceof(object,'IsoDoor') then return object:getNorth() and 'N' or 'W','door' end
    if instanceof(object,'IsoWindow') then return object:getNorth() and 'N' or 'W','window' end
    if instanceof(object,'IsoThumpable') then
        if object:isDoor() then return object:getNorth() and 'N' or 'W','door' end
        if object:isWindowN() then return 'N','window' end
        if object:isWindowW() then return 'W','window' end
    end
    if flag(properties,'WindowN') or flag(properties,'windowN') then return 'N','window' end
    if flag(properties,'WindowW') or flag(properties,'windowW') then return 'W','window' end
    if flag(properties,'DoorWallN') then return 'N','door-frame' end
    if flag(properties,'DoorWallW') then return 'W','door-frame' end
end

local function height(properties)
    if flag(properties,'halfheight') then return G.lowTop end
    -- Surface is a placement height, not a shelf's full bounding height.
    local name=(tostring(properties:get('CustomName') or '')..' '..
        tostring(properties:get('GroupName') or '')):lower()
    for _,word in ipairs({'shel','bookcase','locker','wardrobe','fridge','refriger'}) do
        if name:find(word,1,true) then return G.solidTop end
    end
    local surface=properties:getSurface()
    if surface and surface>0 then return min(G.solidTop,surface/G.surfaceUnitsPerLevel) end
    return G.solidTop
end

function O.square(square,out)
    local x,y,z=square:getX(),square:getY(),square:getZ()
    local objects=square:getObjects()
    local count=objects:size()
    local apertures={}
    -- Only this synchronous square query: object/properties/aperture triples avoid a second native scan.
    -- Door state, barricades and all geometry are read again by the next query; nothing survives here.
    local classified=count>0 and {} or nil
    for i=0,count-1 do
        local object=objects:get(i)
        local properties=object:getProperties()
        local side,kind=aperture(object,properties)
        local at=i*3
        classified[at+1],classified[at+2],classified[at+3]=object,properties,side
        if side and (not apertures[side] or kind~='door-frame') then apertures[side]={kind=kind,object=object} end
    end
    -- Square flags survive removal of glass; keep a structural opening even
    -- when the dedicated window object is gone.
    local properties=square:getProperties()
    for _,side in ipairs({'N','W'}) do
        if not apertures[side] then
            if flag(properties,'Window'..side) or flag(properties,'window'..side) then
                apertures[side]={kind='window'}
            elseif flag(properties,'DoorWall'..side) then apertures[side]={kind='door-frame'} end
        end
    end
    for side,a in pairs(apertures) do
        local object=a.object
        opening(out,x,y,z,side,a.kind=='window' and 'window' or 'door',object)
        local barricaded=object and object.isBarricaded and object:isBarricaded()
        if barricaded then
            edge(out,x,y,z,side,0,1,0,1,'barricade',object)
        elseif a.kind=='door' and object and not object:isDestroyed() then
            -- Native getSpriteEdge(false) follows a swung door's current edge;
            -- true would force its closed edge. The opening itself stays clear.
            local leaf=tostring(object:getSpriteEdge(false))
            edge(out,x,y,z,leaf,0,1,0,G.doorTop,'door',object)
        end
        -- Glass, open and broken windows share a frame-only obstruction query.
        -- This does not break glass, admit a bullet or change PZ collision.
    end
    local function barriers(p,object)
        if not p then return end
        for _,side in ipairs({'N','W'}) do
            if not apertures[side] then
                local low=flag(p,'Hoppable'..side) or (flag(p,'halfheight') and flag(p,'collide'..side))
                local tall=flag(p,'TallHoppable'..side)
                if low or tall or flag(p,'collide'..side) or flag(p,'Wall'..side) or flag(p,'WallNW') then
                    edge(out,x,y,z,side,0,1,0,tall and G.tallFenceTop or (low and G.lowTop or 1),
                        low and not tall and 'low' or 'wall',object)
                end
            end
        end
    end
    barriers(properties)
    for i=0,count-1 do
        local at=i*3
        local object,p,side=classified[at+1],classified[at+2],classified[at+3]
        if not side then
            barriers(p,object)
            if not object:isFloor() and (flag(p,'solid') or flag(p,'solidtrans')) then
                box(out,x,y,z*D.levelHeight,x+1,y+1,(z+height(p))*D.levelHeight,'solid',object)
            end
        end
    end
    if flag(properties,'solidfloor') then
        box(out,x,y,z*D.levelHeight-G.edgeHalfWidth,x+1,y+1,z*D.levelHeight,'floor')
    end
end

-- The hull corners as PZ builds VehiclePoly (its fields are not readable from Lua):
-- the script extents around the centre of mass, through BaseVehicle.getWorldPos.
local cornerVector
local function hull(vehicle,script)
    local extents,mass=script:getExtents(),script:getCenterOfMassOffset()
    if not extents or not mass then return end
    cornerVector=cornerVector or Vector3f.new()
    local hx,hz=extents:x()/2,extents:z()/2
    local points={}
    for _,c in ipairs({{-1,-1},{1,-1},{1,1},{-1,1}}) do
        local v=vehicle:getWorldPos(mass:x()+c[1]*hx,0,mass:z()+c[2]*hz,cornerVector)
        points[#points+1]={v:x(),v:y()}
    end
    return points
end

function O.vehicle(vehicle,out,ray)
    local script=vehicle:getScript()
    if not script then return end
    local extents=script:getExtents()
    if not extents or extents:y()<=0 then return end
    -- A vehicle far from the ray is left before its hull is built (four getWorldPos per vehicle of the cell, every
    -- sample; performance pass 23.10.2026). getWorldPos is rigid, so every corner lies within the local corner's
    -- length of the vehicle's position; two tiles more cover the position lagging the physics body by a frame.
    local mass=ray and script:getCenterOfMassOffset()
    if mass then
        local reach=math.sqrt((math.abs(mass:x())+extents:x()/2)^2+mass:y()^2+(math.abs(mass:z())+extents:z()/2)^2)+2
        local x2=ray.origin[1]+ray.direction[1]*ray.range
        local y2=ray.origin[2]+ray.direction[2]*ray.range
        local radius=ray.radius or 0
        local vx,vy=vehicle:getX(),vehicle:getY()
        if vx+reach<min(ray.origin[1],x2)-radius or vx-reach>max(ray.origin[1],x2)+radius
            or vy+reach<min(ray.origin[2],y2)-radius or vy-reach>max(ray.origin[2],y2)+radius then return end
    end
    local points=hull(vehicle,script)
    if not points then return end
    if ray then
        local x2=ray.origin[1]+ray.direction[1]*ray.range
        local y2=ray.origin[2]+ray.direction[2]*ray.range
        local radius=ray.radius or 0
        local minX,maxX,minY,maxY=math.huge,-math.huge,math.huge,-math.huge
        for _,p in ipairs(points) do
            minX,maxX,minY,maxY=min(minX,p[1]),max(maxX,p[1]),min(minY,p[2]),max(maxY,p[2])
        end
        if maxX<min(ray.origin[1],x2)-radius or minX>max(ray.origin[1],x2)+radius
            or maxY<min(ray.origin[2],y2)-radius or minY>max(ray.origin[2],y2)+radius then return end
    end
    local cx,cy=0,0
    for _,p in ipairs(points) do cx=cx+p[1]/4;cy=cy+p[2]/4 end
    local planes={}
    for i=1,4 do
        local a,b=points[i],points[i%4+1]
        local nx,ny=b[2]-a[2],a[1]-b[1]
        local n=math.sqrt(nx*nx+ny*ny)
        if n<1e-7 then return end
        nx,ny=nx/n,ny/n
        local d=nx*a[1]+ny*a[2]
        if nx*cx+ny*cy>d then nx,ny,d=-nx,-ny,-d end
        planes[#planes+1]={nx,ny,0,d}
    end
    local bottom=vehicle:getZ()*D.levelHeight
    planes[#planes+1]={0,0,1,bottom+extents:y()}
    planes[#planes+1]={0,0,-1,-bottom}
    out[#out+1]={kind='vehicle',object=vehicle,planes=planes}
end

-- Clip a swept bore against convex half-spaces. Radius expands the query, not
-- the actual collision geometry; corner contacts are intentionally conservative.
function O.intersect(ray,shape)
    local first,last=0,ray.range
    local normal
    for _,p in ipairs(shape.planes) do
        local gap=p[4]+(ray.radius or 0)
        local slope=0
        for a=1,3 do gap=gap-p[a]*ray.origin[a];slope=slope+p[a]*ray.direction[a] end
        if abs(slope)<1e-9 then
            if gap<0 then return nil end
        elseif slope>0 then last=min(last,gap/slope)
        else
            local t=gap/slope
            if t>first then first=t;normal={p[1],p[2],p[3]} end
        end
        if first>last or last<0 then return nil end
    end
    return first,normal
end

function O.trace(ray,shapes)
    local result={dist=math.huge,kind='none'}
    for _,shape in ipairs(shapes) do
        local dist,normal=O.intersect(ray,shape)
        if dist and dist<result.dist then result={dist=dist,kind=shape.kind,normal=normal,object=shape.object} end
    end
    return result
end

function O.collect(cell,ray)
    local finish={}
    for a=1,3 do finish[a]=ray.origin[a]+ray.direction[a]*ray.range end
    local radius=ray.radius or 0
    local out,complete={},true
    -- Include neighbouring owners of boundary edges, including an edge lying
    -- just behind the shoulder. Reach is at most the assembled weapon length.
    for x=floor(min(ray.origin[1],finish[1])-radius)-1,floor(max(ray.origin[1],finish[1])+radius)+1 do
        for y=floor(min(ray.origin[2],finish[2])-radius)-1,floor(max(ray.origin[2],finish[2])+radius)+1 do
            for z=floor((min(ray.origin[3],finish[3])-radius)/D.levelHeight),floor((max(ray.origin[3],finish[3])+radius)/D.levelHeight) do
                local square=cell:getGridSquare(x,y,z)
                if square then O.square(square,out) else complete=false end
            end
        end
    end
    local vehicles=cell:getVehicles()
    if vehicles then
        local iterator=vehicles:iterator()
        while iterator:hasNext() do O.vehicle(iterator:next(),out,ray) end
    end
    return out,complete
end

-- Use the actual prop bone; no standing-height substitute for crouch or a
-- missing model. A future pose consumer can pass its pre-obstacle frame here.
function O.ray(player,length,frame)
    if not frame then
        local zb=rawget(_G,'TWZB')
        if type(zb)~='table' or not zb.weaponFrame then return nil end
        local ok,value=pcall(zb.weaponFrameNeutral or zb.weaponFrame,player)
        if not ok then return nil end
        frame=value
    end
    if not frame then return nil end
    for i=1,12 do
        local value=frame[i]
        if type(value)~='number' or value~=value or abs(value)==math.huge then return nil end
    end
    local scale=math.sqrt(frame[7]^2+frame[8]^2+frame[9]^2)
    if scale<1e-7 then return nil end
    local front=length.frontProp
    -- The shoulder datum lies on the bore at the measured rear Y. Using the
    -- butt-pad centre would tilt the ray away from the actual barrel axis.
    local point={front[1],length.rearProp[2],front[3]}
    local u=frame[1]+frame[4]*point[1]+frame[7]*point[2]+frame[10]*point[3]
    local v=frame[2]+frame[5]*point[1]+frame[8]*point[2]+frame[11]*point[3]
    local h=frame[3]+frame[6]*point[1]+frame[9]*point[2]+frame[12]*point[3]
    local angle=player:getLookAngleRadians()
    local cs,sn=math.cos(angle),math.sin(angle)
    local units=C.data.modelScale*scale
    local reach=length.length*scale+D.marginMetres*units
    return {origin={player:getX()+u*cs-v*sn,player:getY()+u*sn+v*cs,player:getZ()*D.levelHeight+h},
        direction={(frame[7]*cs-frame[8]*sn)/scale,(frame[7]*sn+frame[8]*cs)/scale,frame[9]/scale},
        range=reach+D.releaseMetres*units,reach=reach,radius=D.radiusMetres*units,unitsPerMetre=units}
end

function O.smooth(state,hit,ray,dt)
    local depth=ray.reach-hit.dist
    if state.active then
        if depth < -D.releaseMetres*ray.unitsPerMetre then state.active=false end
    elseif depth>D.enterMetres*ray.unitsPerMetre then state.active=true end
    -- A short pistol can consume its entire forward space before reaching
    -- EFT's rifle-sized depth threshold. Full overlap must still yield 1.
    local fullDepth=min(D.source.depthMetres*ray.unitsPerMetre,ray.reach)
    local target=state.active and max(D.latchedRatio,min(1,depth/fullDepth)) or 0
    local rate=target>(state.ratio or 0) and D.source.inRate or D.source.outRate
    local weight=1-math.exp(-rate*max(0,min(dt,.25)))
    state.ratio=(state.ratio or 0)+(target-(state.ratio or 0))*weight
    if target==0 and state.ratio<.0001 then state.ratio=0 end
    state.target=target
    state.dist,state.kind,state.normal=hit.dist,hit.kind,hit.normal
    state.distMetres=hit.dist/ray.unitsPerMetre
    state.depthMetres=depth/ray.unitsPerMetre
    return state
end

local function clear(state,reason)
    state.ratio,state.target,state.active=0,0,false
    state.dist,state.distMetres,state.kind=math.huge,math.huge,'none'
    state.normal,state.ray,state.length,state.hit,state.sampleAt=nil,nil,nil,nil,nil
    state.pose,state.class,state.complete,state.depthMetres=nil,nil,nil,nil
    state.enabled,state.reason=false,reason
    return state
end

function O.state(player)
    local state=O.players[player]
    if not state then state=clear({},'inactive');O.players[player]=state end
    return state
end

function O.update(player,now)
    if not player then return nil end
    local state=O.state(player)
    if not player:isLocalPlayer() or player:isDead() or player:getVehicle() then return clear(state,'player') end
    if not O.enabled() then return clear(state,'view') end
    local weapon=player:getPrimaryHandItem()
    local key=R.visualKey(weapon)
    if not key then return clear(state,'weapon') end
    now=now or getTimestampMs()/1000
    local dt=state.updatedAt and max(0,now-state.updatedAt) or D.sampleSeconds
    state.updatedAt=now
    if state.weapon~=weapon then clear(state,'weapon-change');state.weapon=weapon end
    if not state.sampleAt or now-state.sampleAt>=D.sampleSeconds then
        local length=Length.weapon(weapon)
        if not length then return clear(state,'geometry') end
        local ray=O.ray(player,length)
        if not ray then logOnce('animated weapon frame');return clear(state,'frame') end
        local cell=getCell()
        if not cell then return clear(state,'cell') end
        local shapes,complete=O.collect(cell,ray)
        state.ray,state.length,state.hit=ray,length,O.trace(ray,shapes)
        state.complete,state.sampleAt=complete,now
        state.class=(length.kind=='handgun' or length.kind=='revolver') and 'handgun' or 'rifle'
        state.pose=D.classes[state.class]
    end
    state.enabled,state.reason=true,nil
    return O.smooth(state,state.hit,state.ray,dt)
end

O.bindOptions()
Events.OnGameBoot.Add(O.bindOptions)
Events.OnGameStart.Add(O.bindOptions)
Events.OnPlayerUpdate.Add(function(player)
    if not player or not player:isLocalPlayer() then return end
    local ok,err=pcall(O.update,player)
    if not ok then clear(O.state(player),'api');logOnce(tostring(err)) end
end)
return O
