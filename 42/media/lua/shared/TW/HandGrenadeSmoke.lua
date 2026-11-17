-- Native PZ smoke supplies LOS and zombie perception. VP imagery is L2's API.
local D=require('TW/Data/HandGrenades')
local M={clouds={},index={},keys={},native={},indexedClouds=0,shape={}}
-- Performance pass 23.10.2026: a cloud is indexed every tick for 90-120 s and LOS queries step through
-- cells; the key strings of a cell are made once (the same tostring text). Zero and NaN are formatted each
-- time (-0 and 0 are one table key, NaN none); the cache starts over past 8192 keys.
local keyCache,keyCount={},0
local function indexKey(x,y,z)
    if x==0 or y==0 or z==0 or x~=x or y~=y or z~=z then return tostring(x)..':'..tostring(y)..':'..tostring(z) end
    local zx=keyCache[z]
    if not zx then zx={};keyCache[z]=zx end
    local xy=zx[x]
    if not xy then xy={};zx[x]=xy end
    local key=xy[y]
    if key then return key end
    if keyCount>=8192 then
        keyCache,keyCount={},0
        zx={};keyCache[z]=zx;xy={};zx[x]=xy
    end
    key=tostring(x)..':'..tostring(y)..':'..tostring(z)
    xy[y]=key;keyCount=keyCount+1
    return key
end
function M.visionEnabled() return not (SandboxVars and SandboxVars.EscapeFromZomboidFirearms and SandboxVars.EscapeFromZomboidFirearms.HandGrenadeSmokeVision==false) end
local function configure()
    local api=(isServer and isServer()) and HandGrenadeSmokeServer or HandGrenadeNative
    if api and api.smokeVision then api.smokeVision(M.visionEnabled(),M.indexedClouds>0) end
end
local function unindex(id)
    local keys=M.keys[id]
    if not keys then return end
    for _,key in ipairs(keys) do
        local entry=M.index[key]
        if entry then entry[id]=nil;entry.count=entry.count-1;if entry.count==0 then M.index[key]=nil end end
    end
    M.keys[id]=nil;M.indexedClouds=M.indexedClouds-1;M.shape[id]=nil
end
function M.curve(keys,t)
    if #keys==0 then return 0 end
    if t<=keys[1].time then return keys[1].value end
    for i=1,#keys-1 do
        local a,b=keys[i],keys[i+1]
        if t<=b.time then
            if a.outSlope=='Infinity' or b.inSlope=='Infinity' then return a.value end
            local dt=b.time-a.time
            local u=(t-a.time)/dt
            return (2*u^3-3*u^2+1)*a.value+(u^3-2*u^2+u)*dt*a.outSlope
                +(-2*u^3+3*u^2)*b.value+(u^3-u^2)*dt*b.inSlope
        end
    end
    return keys[#keys].value
end
function M.radius(tpl,elapsed)
    local row=D.items[tpl]
    local r=D.resources[tpl]
    local curve=r.settings._sizeOverTime
    return r.smokeRadiusM*math.max(0,math.min(1,M.curve(curve.m_Curve,elapsed/row.EmitTime)))
end
function M.indexCloud(s)
    if s.phase~='smoke' then unindex(s.id);configure();return end
    local radius=M.radius(s.tpl,s.smokeElapsed or 0)/D.throw.metresPerCell
    local cells=math.ceil(radius)
    -- Performance pass 23.10.2026: the indexed cells are every cell whose centre lies within the radius
    -- (the loop's box always holds them), so about one centre they grow or shrink as nested sets: the
    -- same centre and floor with the same count is the same set, kept as indexed instead of rebuilt.
    local count=0
    for x=math.floor(s.x)-cells,math.floor(s.x)+cells do
        for y=math.floor(s.y)-cells,math.floor(s.y)+cells do
            if (x+.5-s.x)^2+(y+.5-s.y)^2<=radius*radius then count=count+1 end
        end
    end
    local z=math.floor(s.z)
    local shape=M.shape[s.id]
    if not (shape and shape.keys==M.keys[s.id] and shape.index==M.index and shape.x==s.x and shape.y==s.y and shape.z==z and shape.count==count) then
        unindex(s.id)
        local keys={}
        for x=math.floor(s.x)-cells,math.floor(s.x)+cells do
            for y=math.floor(s.y)-cells,math.floor(s.y)+cells do
                if (x+.5-s.x)^2+(y+.5-s.y)^2<=radius*radius then
                    local key=indexKey(x,y,math.floor(s.z))
                    local entry=M.index[key] or {count=0};M.index[key]=entry
                    entry[s.id]=true;entry.count=entry.count+1;keys[#keys+1]=key
                end
            end
        end
        M.keys[s.id]=keys;M.indexedClouds=M.indexedClouds+1
        M.shape[s.id]={keys=keys,index=M.index,x=s.x,y=s.y,z=z,count=count}
    end
    local api=(isServer and isServer()) and HandGrenadeSmokeServer or HandGrenadeNative
    M.native[s.id]=api and api.smokeCloud and api.smokeCloud(s.id,s.x,s.y,s.z,radius)==true
    configure()
end
function M.blocksLine(ax,ay,az,bx,by,bz,states)
    if M.indexedClouds==0 or not M.visionEnabled() or math.floor(az)~=math.floor(bz) then return false end
    local dx,dy=bx-ax,by-ay
    local x,y,z=math.floor(ax),math.floor(ay),math.floor(az)
    local sx,sy=dx>0 and 1 or -1,dy>0 and 1 or -1
    local tx=dx==0 and math.huge or ((dx>0 and x+1 or x)-ax)/dx
    local ty=dy==0 and math.huge or ((dy>0 and y+1 or y)-ay)/dy
    local stepx,stepy=dx==0 and math.huge or 1/math.abs(dx),dy==0 and math.huge or 1/math.abs(dy)
    for _=1,math.ceil(math.abs(dx))+math.ceil(math.abs(dy))+3 do
        if M.index[indexKey(x,y,z)] then return true end
        if math.min(tx,ty)>1 then return false end
        if tx<=ty then x=x+sx;tx=tx+stepx else y=y+sy;ty=ty+stepy end
    end
    return false
end
local function remove(obj)
    obj:removeFromWorld();obj:removeFromSquare()
end
function M.stop(id)
    local api=(isServer and isServer()) and HandGrenadeSmokeServer or HandGrenadeNative
    if api and api.smokeDrop then api.smokeDrop(id) end
    M.native[id]=nil
    unindex(id);configure()
    for _,obj in pairs(M.clouds[id] or {}) do remove(obj) end
    M.clouds[id]=nil
end
function M.apply(s)
    if s.phase~='smoke' then M.stop(s.id);return end
    local row=D.items[s.tpl]
    local age=s.smokeElapsed or 0
    if age>=row.EmitTime then M.stop(s.id);return end
    M.indexCloud(s)
    if M.native[s.id] then return end -- Own native perception; no duplicate fire leases or image.
    if not (IsoFire and IsoFireManager) then return end
    local radius=M.radius(s.tpl,age)
    local cells=math.ceil(radius/D.throw.metresPerCell)
    local list=M.clouds[s.id] or {};M.clouds[s.id]=list
    local keep={}
    for x=math.floor(s.x)-cells,math.floor(s.x)+cells do
        for y=math.floor(s.y)-cells,math.floor(s.y)+cells do
            local distance=math.sqrt((x+0.5-s.x)^2+(y+0.5-s.y)^2)*D.throw.metresPerCell
            local sq=getCell():getGridSquare(x,y,math.floor(s.z))
            if sq and distance<=radius then
                local key=tostring(x)..':'..tostring(y)..':'..tostring(math.floor(s.z))
                keep[key]=true
                local smoke=list[key]
                if smoke and (smoke:getSquare()~=sq or smoke:getLife()<=0) then remove(smoke);smoke=nil;list[key]=nil end
                if not smoke then
                    -- A cold load may already contain our tagged native object.
                    local objects=sq:getObjects()
                    for i=0,objects:size()-1 do
                        local object=objects:get(i)
                        if instanceof(object,'IsoFire') and object:getModData().TWHandSmoke==s.id then smoke=object;break end
                    end
                    if not smoke then
                        if IsoFire.CanAddSmoke(sq,false) then
                            smoke=IsoFire.new(getCell(),sq,false,0,math.ceil((row.EmitTime-age)*60),true)
                            smoke:getModData().TWHandSmoke=s.id
                            local api=(isServer and isServer()) and HandGrenadeSmokeServer or HandGrenadeNative
                            if api and api.stampSmoke then api.stampSmoke(smoke) end
                            -- Special objects participate in native TestVision.
                            -- The own Java hook only admits this tagged smoke.
                            sq:AddSpecialObject(smoke);smoke:addToWorld();sq:setSquareChanged()
                        end
                    end
                    list[key]=smoke
                end
                -- Native two-tick lease: authority's EmitTime clock renews it.
                -- Even a cold-loaded orphan without the optional server patch
                -- expires naturally instead of retaining a long saved lifetime.
                if smoke then smoke:setLife(2) end
            end
        end
    end
    local stale={}
    for key in pairs(list) do if not keep[key] then stale[#stale+1]=key end end
    for _,key in ipairs(stale) do remove(list[key]);list[key]=nil end
end
function M.reset()
    local all={}
    for id in pairs(M.clouds) do all[id]=true end
    for id in pairs(M.keys) do all[id]=true end
    for id in pairs(M.native) do all[id]=true end
    local ids={} for id in pairs(all) do ids[#ids+1]=id end
    for _,id in ipairs(ids) do M.stop(id) end
end
return M
