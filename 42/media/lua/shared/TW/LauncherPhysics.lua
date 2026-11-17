-- Sweep only registered TW impact ammunition; stock integration remains one tick.
local Physics=require('ExplosivesSystems/Physics')
local Factory=require('ExplosivesSystems/OrdnanceFactory')
local M={}
local registered={}
local installed=false
local EPS=1e-9
local PREFIX='EscapeFromZomboidFirearms.'

local function firstWall(ord,scale)
    local square=ord.square
    local sx,sy,sz=square:getX(),square:getY(),square:getZ()
    local x,y=sx+ord.x,sy+ord.y
    local dx,dy=ord.velocityX*Physics.XY_STEP*scale,ord.velocityY*Physics.XY_STEP*scale
    local dz=(ord.velocityZ-Physics.GRAVITY*scale)*Physics.Z_STEP*scale
    local ix,iy=math.floor(x),math.floor(y)
    local signX=dx>0 and 1 or -1
    local signY=dy>0 and 1 or -1
    local tx=dx==0 and math.huge or ((dx>0 and ix+1 or ix)-x)/dx
    local ty=dy==0 and math.huge or ((dy>0 and iy+1 or iy)-y)/dy
    local stepX=dx==0 and math.huge or 1/math.abs(dx)
    local stepY=dy==0 and math.huge or 1/math.abs(dy)
    local dirX=dx>0 and IsoDirections.E or IsoDirections.W
    local dirY=dy>0 and IsoDirections.S or IsoDirections.N
    local cell=getCell()
    -- A segment crosses no more than ceil(|dx|)+ceil(|dy|)+2 grid planes.
    for _=1,math.ceil(math.abs(dx))+math.ceil(math.abs(dy))+2 do
        local t=math.min(tx,ty)
        if t>1 then return end
        local from=cell:getGridSquare(ix,iy,sz)
        if not from then return end -- Never synthesize unloaded world geometry.
        local z=ord.z+dz*t
        if z<=0 and from:getFloor() then return end -- Floor was reached first.
        local crossX=tx<=ty+EPS
        local crossY=ty<=tx+EPS
        local nextX=crossX and cell:getGridSquare(ix+signX,iy,sz) or nil
        local nextY=crossY and cell:getGridSquare(ix,iy+signY,sz) or nil
        local blockX=crossX and Physics.isBlockedBetweenSquares(from,nextX,dirX,z)
        local blockY=crossY and Physics.isBlockedBetweenSquares(from,nextY,dirY,z)
        if crossX and crossY then
            -- A corner touches four faces: include the two incoming faces too.
            local diagonal=cell:getGridSquare(ix+signX,iy+signY,sz)
            blockX=blockX or Physics.isBlockedBetweenSquares(nextY,diagonal,dirX,z)
            blockY=blockY or Physics.isBlockedBetweenSquares(nextX,diagonal,dirY,z)
        end
        if blockX or blockY then return from,x+dx*t,y+dy*t,z,blockX,blockY end
        if crossX then ix=ix+signX;tx=tx+stepX end
        if crossY then iy=iy+signY;ty=ty+stepY end
    end
end

function M.install(config)
    for full in pairs(config.ordnance or {}) do
        if full:sub(1,#PREFIX)==PREFIX then
            local params=Factory.GetAmmoParams(full)
            if params and params.directProjectile and params.detonateOnImpact then registered[params]=full end
        end
    end
    if installed then return end
    installed=true
    local original=Physics.stepOrdnance
    Physics.stepOrdnance=function(ord,scale)
        -- The factory creates a merged table; authority and visual rows retain it.
        local full=ord and registered[ord.params]
        if not full or (ord.sourceWeapon and ord.sourceWeapon~=full) then return original(ord,scale) end
        local square,x,y,z,blockX,blockY=firstWall(ord,scale)
        if not square then return original(ord,scale) end
        local dragXY=math.pow(Physics.DRAG_XY,scale)
        ord.velocityX=ord.velocityX*dragXY
        ord.velocityY=ord.velocityY*dragXY
        ord.velocityZ=(ord.velocityZ-Physics.GRAVITY*scale)*math.pow(Physics.DRAG_Z,scale)
        if blockX then ord.velocityX=-ord.velocityX*Physics.BOUNCE_RESTITUTION_WALL end
        if blockY then ord.velocityY=-ord.velocityY*Physics.BOUNCE_RESTITUTION_WALL end
        ord.square=square;ord.x=x-square:getX();ord.y=y-square:getY();ord.z=z
        ord.prevWorldZ=square:getZ()+z
        -- Terminal impact at the wall, without the stock post-bounce position jump.
        -- Lifecycle code still owns the single payload, visual removal and timer.
        return 'wallhit',false
    end
end
return M
