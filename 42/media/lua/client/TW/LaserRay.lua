-- World tracing ported from the Brita B42 port (same author); emitters come
-- from the installed Tarkov device instead of weapon-family profiles.
local P=require('TW/LaserGeometry')
local R={EPS=.0001}
local abs,min,max,floor=math.abs,math.min,math.max,math.floor
-- The trace in Java (zb/WorldRay.java, performance pass 23.10.2026): the same scene, shapes, order and
-- arithmetic as the Lua below, without the tens of thousands of reflective calls per trace that cost the
-- frame rate with lasers and lights on. R.legacy (QA A/B and parity) or no Java part: the Lua form below.
R.legacy=false
R.legacyIso=false
local configured
function R.native()
    if R.legacy or R.legacyIso then return nil end
    local zb=rawget(_G,'TWZB')
    if type(zb)~='table' or not zb.rayTrace then return nil end
    if not configured then zb.rayConfig(P.verticalScale,P.glassTransmission,P.windowBottom,P.windowTop);configured=true end
    return zb
end
local function cachedFlag(p,n,cache)
    if not p then return false end
    if not cache then return p:has(IsoFlagType[n]) end
    local values=cache[p] or {};cache[p]=values
    if values[n]==nil then values[n]=p:has(IsoFlagType[n]) end
    return values[n]
end
local function box(out,obj,x1,y1,z1,x2,y2,z2,kind,trans,space)
    out[#out+1]={object=obj,lo={x1,y1,z1},hi={x2,y2,z2},kind=kind or 'opaque',transmission=trans,space=space,level=floor(z1+.01)}
end
R.box=box
function R.cylinder(out,obj,x,y,z,r,height,kind)
    box(out,obj,x-r,y-r,z,x+r,y+r,z+height,kind)
    out[#out].cylinder={x=x,y=y,r=r}
end
-- Slab intersection keeps the surface normal for the projected impact spot.
function R.intersect(ray,s)
    local ox,oy,dx,dy=ray.x,ray.y,ray.dx,ray.dy
    if s.space then
        local b=s.space;local x,y=ox-b.x,oy-b.y
        ox,oy=x*b.fy-y*b.fx,x*b.fx+y*b.fy
        dx,dy=dx*b.fy-dy*b.fx,dx*b.fx+dy*b.fy
    end
    local enter,leave,axis,sign=0,ray.range,0,0
    for i=1,3 do
        local o,d
        if i==1 then o,d=ox,dx elseif i==2 then o,d=oy,dy else o,d=ray.z,ray.dz end
        if abs(d)<.0000001 then
            if o<s.lo[i]-R.EPS or o>s.hi[i]+R.EPS then return end
        else
            local a,b=(s.lo[i]-o)/d,(s.hi[i]-o)/d
            local normal=-1
            if a>b then a,b=b,a;normal=1 end
            if a>enter then enter,axis,sign=a,i,normal end
            leave=min(leave,b)
            if enter>leave+R.EPS then return end
        end
    end
    if s.cylinder then
        local c=s.cylinder;local x,y=ox-c.x,oy-c.y
        local a=dx*dx+dy*dy;local b=2*(x*dx+y*dy);local q=x*x+y*y-c.r*c.r
        if a<.0000001 then if q>0 then return end
        else
            local discriminant=b*b-4*a*q;if discriminant<0 then return end
            local t1,t2=(-b-math.sqrt(discriminant))/(2*a),(-b+math.sqrt(discriminant))/(2*a)
            if t1>enter then enter,axis=t1,4 end
            leave=min(leave,t2);if enter>leave+R.EPS then return end
        end
    end
    if leave<R.EPS or enter>ray.range then return end
    local nx,ny,nz=0,0,0
    if axis==1 then nx=sign elseif axis==2 then ny=sign elseif axis==3 then nz=sign
    elseif axis==4 then nx=(ox+dx*enter-s.cylinder.x)/s.cylinder.r;ny=(oy+dy*enter-s.cylinder.y)/s.cylinder.r end
    if s.space then local b=s.space;nx,ny=nx*b.fy+ny*b.fx,-nx*b.fx+ny*b.fy end
    return max(0,enter),leave,nx,ny,nz
end
function R.cast(ray,shapes,viewPlayer,viewFloor,skipObject)
    local hits={}
    for _,s in ipairs(shapes) do
        local visible=not (skipObject and s.object==skipObject)
        if visible and viewPlayer and s.object then visible=s.object:getAlpha(viewPlayer)>.12 end
        if visible and viewFloor and s.level>viewFloor then visible=false end
        if visible then
            local t,exit,nx,ny,nz=R.intersect(ray,s)
            if t then hits[#hits+1]={distance=t,exit=exit,shape=s,nx=nx,ny=ny,nz=nz} end
        end
    end
    table.sort(hits,function(a,b) return a.distance<b.distance end)
    local result={distance=ray.range,power=1,transmissions={},reason='range',ray=ray}
    local transmitted={}
    for _,h in ipairs(hits) do
        local s=h.shape
        if not s.transmission then
            result.distance=h.distance;result.hit=h;result.reason=s.kind;break
        elseif not transmitted[s.kind=='vehicle-glass' and s or (s.object or s)] then
            transmitted[s.kind=='vehicle-glass' and s or (s.object or s)]=true
            result.power=result.power*s.transmission
            result.transmissions[#result.transmissions+1]={distance=h.distance,power=result.power}
        end
    end
    result.x=ray.x+ray.dx*result.distance;result.y=ray.y+ray.dy*result.distance;result.z=ray.z+ray.dz*result.distance
    return result
end
local function edge(out,obj,x,y,z,n,u1,u2,h1,h2,kind,trans)
    if h2<=h1 or u2<=u1 then return end
    if n=='N' then box(out,obj,x+u1,y-.003,z+h1,x+u2,y+.003,z+h2,kind,trans)
    elseif n=='W' then box(out,obj,x-.003,y+u1,z+h1,x+.003,y+u2,z+h2,kind,trans)
    elseif n=='S' then edge(out,obj,x,y+1,z,'N',u1,u2,h1,h2,kind,trans)
    elseif n=='E' then edge(out,obj,x+1,y,z,'W',u1,u2,h1,h2,kind,trans) end
end
local function frame(out,obj,x,y,z,n,window)
    local bottom,top=window and P.windowBottom or 0,window and P.windowTop or .96
    edge(out,obj,x,y,z,n,0,.055,0,1,'frame');edge(out,obj,x,y,z,n,.945,1,0,1,'frame')
    edge(out,obj,x,y,z,n,.055,.945,0,bottom,'frame');edge(out,obj,x,y,z,n,.055,.945,top,1,'frame')
end
local function aperture(obj)
    if instanceof(obj,'IsoWindow') then return obj:getNorth() and 'N' or 'W','window' end
    if instanceof(obj,'IsoDoor') then return obj:getNorth() and 'N' or 'W','door' end
    if instanceof(obj,'IsoThumpable') then
        if obj:isDoor() then return obj:getNorth() and 'N' or 'W','door' end
        if obj:isWindowN() then return 'N','window' elseif obj:isWindowW() then return 'W','window' end
    end
end
local function barricade(out,obj,x,y,z,n)
    if obj:isMetal() then edge(out,obj,x,y,z,n,.055,.945,P.windowBottom,P.windowTop,'metal')
    elseif obj:isMetalBar() then
        for i=1,5 do edge(out,obj,x,y,z,n,i/6-.02,i/6+.02,P.windowBottom,P.windowTop,'bar') end
    else
        local count=obj:getNumPlanks()
        for i=1,count do
            local h=P.windowBottom+(i-1)*(P.windowTop-P.windowBottom)/4
            edge(out,obj,x,y,z,n,.055,.945,h,h+(P.windowTop-P.windowBottom)/4,'plank')
        end
    end
end
function R.squareShapes(sq,out,owner,seen,flags)
    local function flag(p,n) return cachedFlag(p,n,flags) end
    local x,y,z=sq:getX(),sq:getY(),sq:getZ()
    local objects=sq:getObjects();local openings={}
    for i=0,objects:size()-1 do local n,k=aperture(objects:get(i));if n then openings[n]=k end end
    local floorObject=sq:getFloor()
    if flag(sq:getProperties(),'solidfloor') then box(out,floorObject,x,y,z-.003,x+1,y+1,z+.003,'floor') end
    for i=0,objects:size()-1 do
        local obj=objects:get(i)
        if obj~=floorObject and not seen[obj] then
            seen[obj]=true
            local p=obj:getProperties();local sprite=obj:getSprite();local name=sprite and sprite:getName() or ''
            name=name and name:lower() or ''
            local n,k=aperture(obj)
            if instanceof(obj,'IsoWindow') then
                frame(out,obj,x,y,z,n,true)
                if not obj:IsOpen() and not obj:isSmashed() then edge(out,obj,x,y,z,n,.055,.945,P.windowBottom,P.windowTop,'glass',P.glassTransmission) end
            elseif k=='door' then
                frame(out,obj,x,y,z,n,false)
                if not obj:isDestroyed() then
                    local direction=obj:getSpriteEdge(false)
                    edge(out,obj,x,y,z,tostring(direction),.02,.98,0,.96,'door')
                end
                if instanceof(obj,'IsoDoor') and obj:HasCurtains() and not obj:isCurtainOpen() then edge(out,obj,x,y,z,tostring(obj:getSpriteEdge(false)),.02,.98,.15,.85,'curtain') end
            elseif k=='window' then frame(out,obj,x,y,z,n,true)
            elseif instanceof(obj,'IsoCurtain') then
                local direction=tostring(obj:getType()):match('curtain([NWSE])') or (obj:getNorth() and 'N' or 'W')
                if not obj:IsOpen() then edge(out,obj,x,y,z,direction,.02,.98,.15,.9,'curtain') end
            elseif instanceof(obj,'IsoBarricade') then
                local dir=tostring(obj:getDir());barricade(out,obj,x,y,z,dir)
            elseif instanceof(obj,'IsoTree') then
                local radius=.045+obj:getSize()*.018
                R.cylinder(out,obj,x+.5,y+.5,z,radius,1.6,'trunk')
            else
                local isEdge=false
                for _,d in ipairs({'N','W'}) do
                    local wall=flag(p,'Wall'..d) or flag(p,'cut'..d) or flag(p,'DoorWall'..d)
                    local window=flag(p,'window'..d);local door=flag(p,'door'..d)
                    local low=flag(p,'Hoppable'..d);local tall=flag(p,'TallHoppable'..d)
                    if wall or window or door or low or tall then
                        isEdge=true
                        local opening=openings[d] or (window and 'window') or (door and 'door')
                        if opening then frame(out,obj,x,y,z,d,opening=='window')
                        elseif low or tall then
                            local height=tall and .95 or .34
                            if flag(p,'transparent'..d) then
                                -- Separate posts and rails: an opening is not a probabilistic blocker.
                                for j=0,3 do edge(out,obj,x,y,z,d,max(0,j/3-.022),min(1,j/3+.022),0,height,'fence') end
                                for j=1,3 do local h=height*j/3;edge(out,obj,x,y,z,d,0,1,h-.012,h+.012,'fence') end
                            else edge(out,obj,x,y,z,d,0,1,0,height,'fence') end
                        elseif flag(p,'Wall'..d..'Trans') or flag(p,'transparent'..d) then edge(out,obj,x,y,z,d,0,1,0,1,'glass',P.glassTransmission)
                        else edge(out,obj,x,y,z,d,0,1,0,1,'wall') end
                    end
                end
                if flag(p,'WallSE') then
                    -- Diagonal walls need a local coordinate frame along their face.
                    local b={x=x+.5,y=y+.5,fx=.70710678,fy=-.70710678}
                    box(out,obj,-.004,-.707,z,.004,.707,z+1,'wall',nil,b);isEdge=true
                end
                if not isEdge and (flag(p,'solid') or flag(p,'solidtrans')) then
                    -- IsoSprite.isBush is a Java field (invisible to Lua); use the tile flags/name.
                    if flag(p,'canBeCut') or flag(p,'vegitation') or name:find('bush',1,true) then box(out,obj,x+.1,y+.1,z,x+.9,y+.9,z+.5,'foliage',.8)
                    else box(out,obj,x+.07,y+.07,z,x+.93,y+.93,z+P.furnitureHeight(name,p),'furniture') end
                end
            end
        end
    end
    local moving=sq:getMovingObjects()
    for i=0,moving:size()-1 do
        local obj=moving:get(i)
        if obj~=owner and not seen[obj] and instanceof(obj,'IsoGameCharacter') then
            seen[obj]=true
            if not R.legacyIso then
                local sitting=(instanceof(obj,'IsoZombie') and obj.isSitAgainstWall and obj:isSitAgainstWall())
                    or (obj.isSitOnGround and obj:isSitOnGround())
                    or (obj.isSittingOnFurniture and obj:isSittingOnFurniture())
                if sitting then
                    R.cylinder(out,obj,obj:getX(),obj:getY(),obj:getZ()+.04,.22,.46-.04,'body')
                elseif obj:isOnFloor() then
                    box(out,obj,obj:getX()-.35,obj:getY()-.2,obj:getZ()+.04,obj:getX()+.35,obj:getY()+.2,obj:getZ()+.15,'body')
                else
                    local h=obj:isSneaking() and .5 or .73
                    R.cylinder(out,obj,obj:getX(),obj:getY(),obj:getZ()+.04,.20,h-.04,'body')
                end
            else
                local h=.73
                if obj:isOnFloor() then h=.15 elseif obj:isSneaking() then h=.5 end
                if obj:isOnFloor() then box(out,obj,obj:getX()-.35,obj:getY()-.2,obj:getZ()+.04,obj:getX()+.35,obj:getY()+.2,obj:getZ()+h,'body')
                else R.cylinder(out,obj,obj:getX(),obj:getY(),obj:getZ()+.04,.16,h-.04,'body') end
            end
        end
    end
end
function R.vehicleShapes(v,out)
    local script=v:getScript();if not script then return end
    local e=script:getExtents();local direction=v:getForwardVector(Vector3f.new())
    local fx,fy=direction:x(),direction:z();local length=math.sqrt(fx*fx+fy*fy)
    if length<.01 then return end
    local b={x=v:getX(),y=v:getY(),fx=fx/length,fy=fy/length}
    local w,l,z=e:x()/2,e:z()/2,v:getZ()
    local roof=max(.48,e:y()/P.verticalScale);local bottom=min(.62,roof*.57)
    box(out,v,-w,-l,z+.03,w,l,z+bottom,'vehicle-body',nil,b)
    box(out,v,-w,-l*.35,z+roof-.035,w,l*.35,z+roof,'vehicle-roof',nil,b)
    local function windowState(id)
        local part=v:getPartById(id)
        local window=part and part:getWindow()
        if not window then return 'panel' end
        return not window:isDestroyed() and not window:isOpen() and 'glass' or 'open'
    end
    local function pane(id,x1,y1,x2,y2)
        local state=windowState(id)
        if state~='open' then box(out,v,x1,y1,z+bottom,x2,y2,z+roof-.035,'vehicle-'..state,state=='glass' and .86 or nil,b) end
    end
    for _,s in ipairs({-1,1}) do
        local a,c=s<0 and -w or w-.025,s<0 and -w+.025 or w
        local side=s<0 and 'Left' or 'Right'
        pane('WindowFront'..side,a,0,c,l*.35)
        pane('WindowRear'..side,a,-l*.35,c,0)
        for _,t in ipairs({-.35,0,.35}) do
            box(out,v,a,l*t-.025,z+bottom,c,l*t+.025,z+roof,'vehicle-frame',nil,b)
        end
    end
    for _,s in ipairs({-1,1}) do
        local a,c=l*s*.35-.025,l*s*.35+.025
        local id=s>0 and 'Windshield' or 'WindshieldRear'
        pane(id,-w+.04,a,w-.04,c)
    end
end
-- Grid traversal also visits adjacent cells to include objects which touch an edge.
function R.scene(ray,owner,includeVehicles,context)
    context=context or {};context.cell=context.cell or getCell()
    local cell=context.cell;local out,seen,cells={},{},{}
    context.squares=context.squares or {};context.loaded=context.loaded or {};context.flags=context.flags or {}
    local g={floor(ray.x),floor(ray.y),floor(ray.z)};local o={ray.x,ray.y,ray.z};local d={ray.dx,ray.dy,ray.dz}
    local step,nextT,delta={},{},{}
    for i=1,3 do
        step[i]=d[i]>=0 and 1 or -1;delta[i]=abs(d[i])>.000001 and 1/abs(d[i]) or math.huge
        nextT[i]=delta[i]==math.huge and math.huge or ((d[i]>=0 and g[i]+1-o[i] or o[i]-g[i])*delta[i])
    end
    local travelled=0;local unloaded
    local visits=math.ceil(ray.range*(abs(ray.dx)+abs(ray.dy)+abs(ray.dz)))+4
    for visit=1,visits do
        local centerKey=g[1]..':'..g[2]..':'..g[3]
        if context.loaded[centerKey]==nil then context.loaded[centerKey]=cell:getGridSquare(g[1],g[2],g[3]) or false end
        local center=context.loaded[centerKey]
        if not center then
            local baseKey=g[1]..':'..g[2]..':0'
            if context.loaded[baseKey]==nil then context.loaded[baseKey]=cell:getGridSquare(g[1],g[2],0) or false end
            -- Missing upper squares in a loaded column are ordinary open air.
            if g[3]<=0 or not context.loaded[baseKey] then unloaded=travelled;break end
        end
        for ax=-1,1 do for ay=-1,1 do
            local x,y=g[1]+ax,g[2]+ay;local key=x..':'..y..':'..g[3]
            if not cells[key] then
                cells[key]=true;local shapes=context.squares[key]
                if not shapes then
                    if context.loaded[key]==nil then context.loaded[key]=cell:getGridSquare(x,y,g[3]) or false end
                    local sq=context.loaded[key];shapes={}
                    -- Flags are read live (no context.flags): Kahlua tables keyed by PropertyContainer mixed up different
                    -- containers (game QA 23.10.2026, perfdiff: an item on the floor took the next wall's WallSE, an
                    -- upper floor lost its solidfloor), so beams met walls and floors that are not there. As WorldRay.
                    if sq then R.squareShapes(sq,shapes,owner,{},nil) end
                    context.squares[key]=shapes
                end
                for _,shape in ipairs(shapes) do if not seen[shape] then seen[shape]=true;out[#out+1]=shape end end
            end
        end end
        local t=min(nextT[1],nextT[2],nextT[3]);if t>ray.range then break end
        travelled=t
        for i=1,3 do if nextT[i]<=t+R.EPS then g[i]=g[i]+step[i];nextT[i]=nextT[i]+delta[i] end end
    end
    if includeVehicles then
        if not context.vehicles then
            context.vehicles={}
            local vehicles=cell:getVehicles();local iterator=vehicles:iterator()
            while iterator:hasNext() do
                local v=iterator:next()
                if abs(v:getX()-ray.x)+abs(v:getY()-ray.y)<ray.range*1.42+12 then R.vehicleShapes(v,context.vehicles) end
            end
        end
        for _,shape in ipairs(context.vehicles) do out[#out+1]=shape end
    end
    return out,unloaded,context
end
function R.trace(ray,owner)
    local zb=R.native()
    local traced=zb and zb.rayTrace(ray,owner)
    if traced then return traced end
    local aim=not R.legacyIso and ray.aim
    local skipTarget=aim and aim.mode=='ballistics' and aim.target or nil
    local shapes,unloaded,context=R.scene(ray,owner,true)
    local result=R.cast(ray,shapes,nil,nil,skipTarget)
    result.context=context
    if unloaded and unloaded<result.distance then
        result.distance=unloaded;result.hit=nil;result.reason='unloaded'
        result.x=ray.x+ray.dx*unloaded;result.y=ray.y+ray.dy*unloaded;result.z=ray.z+ray.dz*unloaded
    end
    if aim and aim.mode=='ballistics' and aim.targetX and aim.targetY and aim.targetZ then
        local vx,vy,vz=ray.dx,ray.dy,ray.dz*P.verticalScale
        local v2=vx*vx+vy*vy+vz*vz
        if v2>1e-8 then
            local tCenter=((aim.targetX-ray.x)*vx+(aim.targetY-ray.y)*vy+(aim.targetZ-ray.z)*P.verticalScale*vz)/v2
            local tFront=max((ray.begin or 0)+.02,tCenter-.08)
            if tFront>=(ray.begin or 0) and tFront<=ray.range and (not result.hit or tFront<result.distance) then
                result.distance=tFront;result.reason='body'
                result.x=ray.x+ray.dx*tFront;result.y=ray.y+ray.dy*tFront;result.z=ray.z+ray.dz*tFront
                local hl=math.sqrt(ray.dx*ray.dx+ray.dy*ray.dy)
                result.hit=hl>1e-6 and {distance=tFront,exit=tCenter,nx=-ray.dx/hl,ny=-ray.dy/hl,nz=0,kind='body',object=skipTarget}
                    or {distance=tFront,exit=tCenter,nx=0,ny=0,nz=1,kind='body',object=skipTarget}
            end
        end
    end
    return result
end
-- Pick the visible world surface under the cursor. A zero animation pitch is
-- normal when aiming at empty ground in B42; it is not a horizontal laser target.
function R.cursorTarget(player)
    if not player:isLocalPlayer() then return end
    local n=player:getPlayerNum();local z=math.floor(player:getZ())
    local sx,sy=getMouseX(),getMouseY()
    local x,y=screenToIsoX(n,sx,sy,z),screenToIsoY(n,sx,sy,z)
    if (x-player:getX())^2+(y-player:getY())^2>10000 then return end
    local ray={x=x+3.75,y=y+3.75,z=z+1.25,dx=-3,dy=-3,dz=-1,range=1.25,begin=0}
    local zb=R.native()
    local hit=zb and zb.rayCursor(ray,player,n,z) or R.cast(ray,R.scene(ray,player,true),n,z)
    if hit.hit then return hit.x,hit.y,hit.z end
    return x,y,z
end
function R.aimTarget(player)
    if not player:isLocalPlayer() then return end
    local zb=rawget(_G,'TWZB')
    if zb and zb.isoAimTarget then
        local t=R.legacyIso and zb.isoAimTarget(player,true) or zb.isoAimTarget(player)
        if t then return t[1],t[2],t[3],t[4],t[5] end
    end
    if player.isAimAtFloor and player:isAimAtFloor() then
        local tx,ty,tz=R.cursorTarget(player)
        if tx then return tx,ty,tz,'floor' end
    end
    local n=player:getPlayerNum()
    local aimZ=player.getAimOriginPosZ and player:getAimOriginPosZ() or (player:getZ()+.48)
    local sx,sy=getMouseX(),getMouseY()
    local x,y=screenToIsoX(n,sx,sy,aimZ),screenToIsoY(n,sx,sy,aimZ)
    if (x-player:getX())^2+(y-player:getY())^2>10000 then return end
    return x,y,aimZ,'reticle'
end
function R.aimState(player,weapon,beam)
    local raw=player:isLocalPlayer() and player:getAimVector(Vector2.new()) or nil
    local rawLen=raw and math.sqrt(raw:getX()^2+raw:getY()^2) or 0
    local direction=rawLen>=.001 and raw or player:getForwardDirection()
    local dx,dy=direction:getX(),direction:getY()
    local length=math.sqrt(dx*dx+dy*dy)
    -- Idle toggles still need a valid network direction. A local aim vector
    -- can be zero before the player aims; use the native facing direction.
    if length<.001 then return end
    dx,dy=dx/length,dy/length
    local pitch=player:getCurrentVerticalAimAngle()
    local tx,ty,tz,mode,targetObj
    if rawLen>=.001 then tx,ty,tz,mode,targetObj=R.aimTarget(player) end
    local accept=R.legacyIso and (mode=='ballistics' or mode=='floor') or (mode=='ballistics' or mode=='floor' or mode=='reticle')
    if tx and accept then
        local x,y,z=P.origin(player,weapon,beam and beam.position,true,pitch)
        if x then
            local horizontal=math.sqrt((tx-x)^2+(ty-y)^2)
            local forward=(tx-x)*dx+(ty-y)*dy
            if horizontal>.02 and (R.legacyIso or forward>.05) then
                dx,dy=(tx-x)/horizontal,(ty-y)/horizontal
                pitch=math.deg(math.atan2((tz-z)*P.verticalScale,horizontal))
            end
        end
    end
    return {aiming=true,dx=dx,dy=dy,pitch=pitch,targetX=tx,targetY=ty,targetZ=tz,mode=not R.legacyIso and mode or nil,target=not R.legacyIso and targetObj or nil}
end
-- One ray per visible beam. While aiming every device aims along the common
-- line to the target; without aiming (as in Tarkov, where a switched-on
-- device always shines) each beam follows the housing axis of the lowered weapon.
function R.forBeam(player,weapon,beam,aim,maximumRange)
    aim=aim or R.aimState(player,weapon,beam);if not aim then return end
    if not aim.aiming then
        local x,y,z,reach,f=P.origin(player,weapon,beam.position,false)
        if not x then return end
        local vx,vy,vz=P.axis(player,f,weapon,beam and beam.direction)
        if not vx then return end
        -- The invisible prefix from the body keeps a device clipped through a
        -- wall from emitting light; at begin the ray is exactly at the emitter.
        local back=math.max(.05,reach)
        return {x=x-vx*back,y=y-vy*back,z=z-vz*back,dx=vx,dy=vy,dz=vz,range=maximumRange and back+maximumRange or math.min(P.range(player,weapon,beam),back+P.idleRange),begin=back,owner=player,aim=aim}
    end
    local x,y,z,reach=P.origin(player,weapon,beam.position,true,player:getCurrentVerticalAimAngle())
    if not x then return end
    local dx,dy,pitchRad=aim.dx,aim.dy,math.rad(aim.pitch)
    if not R.legacyIso and aim.targetX and aim.targetY and aim.targetZ and (aim.mode=='ballistics' or aim.mode=='floor' or aim.mode=='reticle') then
        local tdx,tdy=aim.targetX-x,aim.targetY-y
        local h=math.sqrt(tdx*tdx+tdy*tdy)
        if h>.02 and tdx*aim.dx+tdy*aim.dy>.05 then
            dx,dy=tdx/h,tdy/h
            pitchRad=math.atan2((aim.targetZ-z)*P.verticalScale,h)
        end
    end
    local vx,vy,vz=dx*math.cos(pitchRad),dy*math.cos(pitchRad),math.sin(pitchRad)/P.verticalScale
    local back=math.max(.05,reach)
    return {x=x-vx*back,y=y-vy*back,z=z-vz*back,dx=vx,dy=vy,dz=vz,range=maximumRange and back+maximumRange or P.range(player,weapon,beam),begin=back,owner=player,aim=aim}
end
function R.visible(x,y,z,n,owner,context)
    -- A Java trace's context (userdata) goes back to Java; a Lua one stays with the Lua form.
    local zb=type(context)~='table' and R.native()
    if zb then return zb.rayVisible(x,y,z,n,owner,context) end
    if type(context)~='table' then context=nil end
    context=context or {};context.vision=context.vision or {};context.alpha=context.alpha or {};context.loaded=context.loaded or {};context.levels=context.levels or {}
    local key=floor(x)..':'..floor(y)..':'..floor(z)
    if context.loaded[key]==nil then context.loaded[key]=getCell():getGridSquare(floor(x),floor(y),floor(z)) or false end
    local sq=context.loaded[key]
    if not sq and floor(z)>0 then
        local baseKey=floor(x)..':'..floor(y)..':0'
        if context.loaded[baseKey]==nil then context.loaded[baseKey]=getCell():getGridSquare(floor(x),floor(y),0) or false end
        sq=context.loaded[baseKey]
    end
    if not sq then return false end
    local visibleSquares=context.vision[n] or {};context.vision[n]=visibleSquares
    if visibleSquares[sq]==nil then visibleSquares[sq]=sq:isCanSee(n) end
    if not visibleSquares[sq] then return false end
    local ray={x=x+.002,y=y+.002,z=z+.00067,dx=3,dy=3,dz=1,range=.95}
    local shapes=R.scene(ray,owner,true,context)
    if context.levels[n]==nil then local viewer=getSpecificPlayer(n);context.levels[n]=viewer and floor(viewer:getZ()) or floor(z) end
    local level=context.levels[n]
    local alpha=context.alpha[n] or {};context.alpha[n]=alpha
    for _,shape in ipairs(shapes) do
        if not shape.transmission and (R.legacyIso or shape.kind~='body') and shape.level<=level and shape.hi[3]>=ray.z then
            local obj=shape.object
            if obj and alpha[obj]==nil then alpha[obj]=obj:getAlpha(n)>.12 end
            if (not obj or alpha[obj]) and R.intersect(ray,shape) then return false end
        end
    end
    return true
end
-- A context shared by several rays (R.blocked), and whether a shape of another kind than skip
-- meets the ray (TW/OpticView: walls, glass and vehicles block a thermal image, bodies do not).
function R.newContext()
    local zb=R.native()
    return zb and zb.rayContext() or {}
end
function R.blocked(ray,owner,context,skip)
    local zb=type(context)~='table' and R.native() or nil
    local blocked
    if zb then blocked=zb.rayBlocked(ray,owner,context,skip) end
    if blocked~=nil then return blocked end
    if type(context)~='table' then context={} end
    for _,s in ipairs(R.scene(ray,owner,true,context)) do
        if s.kind~=skip and R.intersect(ray,s) then return true end
    end
    return false
end
return R
