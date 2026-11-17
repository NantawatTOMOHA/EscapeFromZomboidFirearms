-- Slot cells around the weapon as on the Escape from Tarkov modding screen: every cell sits on one
-- ellipse around the weapon, in the angular order of its mount point seen from the weapon's centre,
-- as near to that direction as the other cells allow, and a leader line runs from the cell to the
-- mount point. Pure geometry in screen pixels (no PZ objects), tested offline.
local Ring={}

local TWO_PI=math.pi*2
local SAMPLES=720
-- Floor modulo: the game's Kahlua takes a % b with the sign of a (Java's remainder), not Lua's.
local function wrap(v,m) return v-math.floor(v/m)*m end
Ring.wrap=wrap

-- Ellipse sampled once per call: points, the parameter of each sample and the cumulative
-- Chebyshev length in cell steps. Two axis-aligned cells of size w (gap g) are apart when their
-- centres are at least w+g apart in x or in y, so a Chebyshev step of w+g along the ring keeps
-- neighbours apart on the flat top as on the steep sides.
local function sample(cx,cy,rx,ry,step)
    local ts,xs,ys,us={},{},{},{}
    local u=0
    for i=0,SAMPLES do
        local t=i/SAMPLES*TWO_PI
        local x,y=cx+rx*math.cos(t),cy+ry*math.sin(t)
        if i>0 then u=u+math.max(math.abs(x-xs[i]),math.abs(y-ys[i]))/step end
        ts[i+1],xs[i+1],ys[i+1],us[i+1]=t,x,y,u
    end
    return {t=ts,x=xs,y=ys,u=us,total=u}
end

-- Ring position (in cell steps) of the ellipse parameter t.
local function uOf(e,t)
    t=wrap(t,TWO_PI)
    local f=t/TWO_PI*SAMPLES
    local i=math.floor(f)
    local a=f-i
    return e.u[i+1]+(e.u[math.min(i+2,SAMPLES+1)]-e.u[i+1])*a
end

-- Point of the ring at position u (cell steps).
local function pointOf(e,u)
    u=wrap(u,e.total)
    local lo,hi=1,SAMPLES+1
    while hi-lo>1 do
        local mid=math.floor((lo+hi)/2)
        if e.u[mid]<=u then lo=mid else hi=mid end
    end
    local span=e.u[hi]-e.u[lo]
    local a=span>0 and (u-e.u[lo])/span or 0
    return e.x[lo]+(e.x[hi]-e.x[lo])*a,e.y[lo]+(e.y[hi]-e.y[lo])*a
end

-- Positions as close to their wishes as possible, in order, each at least 1 after the previous,
-- on a circle of the given length: cut at the widest free arc, then pool adjacent violators
-- (merged runs sit on the mean of their wishes), as for one row of cells.
function Ring.pack(wishes,total)
    local n=#wishes
    if n==0 then return {} end
    local order={}
    for i=1,n do order[i]=i end
    table.sort(order,function(a,b)
        if wishes[a]~=wishes[b] then return wishes[a]<wishes[b] end
        return a<b
    end)
    -- The widest gap between neighbours (circularly) is where the ring is cut open.
    local cut,widest=1,-1
    for k=1,n do
        local here=wishes[order[k]]
        local before=k==1 and wishes[order[n]]-total or wishes[order[k-1]]
        if here-before>widest then widest,cut=here-before,k end
    end
    local seq={}
    for k=0,n-1 do
        local index=order[wrap(cut-1+k,n)+1]
        local w=wishes[index]
        if k>0 and w<seq[k].wish then w=w+total end
        seq[k+1]={index=index,wish=w}
    end
    local runs={}
    for k,entry in ipairs(seq) do
        local run={from=k,n=1,sum=entry.wish}
        runs[#runs+1]=run
        while #runs>1 do
            local a,b=runs[#runs-1],runs[#runs]
            local aStart=a.sum/a.n
            local bStart=b.sum/b.n
            if aStart+a.n<=bStart+1e-9 then break end
            -- b's members follow a's: their wishes count from a's first member.
            a.sum=a.sum+b.sum-b.n*a.n
            a.n=a.n+b.n
            runs[#runs]=nil
        end
    end
    local placed={}
    for _,run in ipairs(runs) do
        local start=run.sum/run.n
        for j=0,run.n-1 do placed[run.from+j]=start+j end
    end
    -- Closing the circle: the last cell may not come round onto the first. A full ring spaces
    -- every cell evenly from where the cells wished to start on average.
    if placed[n]-placed[1]>total-1+1e-9 then
        local step=total/n
        local mean=0
        for k=1,n do mean=mean+seq[k].wish-(k-1)*step end
        mean=mean/n
        for k=1,n do placed[k]=mean+(k-1)*step end
    end
    local result={}
    for k=1,n do result[seq[k].index]=wrap(placed[k],total) end
    return result
end

-- Where a line from the cell's centre towards (ax, ay) leaves the cell.
function Ring.edgePoint(cell,ax,ay)
    local cx,cy=cell.x+cell.w/2,cell.y+cell.h/2
    local dx,dy=ax-cx,ay-cy
    if math.abs(dx)<1e-6 and math.abs(dy)<1e-6 then return cx,cy end
    local sx=math.abs(dx)>1e-6 and (cell.w/2)/math.abs(dx) or math.huge
    local sy=math.abs(dy)>1e-6 and (cell.h/2)/math.abs(dy) or math.huge
    local s=math.min(sx,sy,1)
    return cx+dx*s,cy+dy*s
end

local function overlapping(cells)
    for i=1,#cells do
        local a=cells[i]
        for j=i+1,#cells do
            local b=cells[j]
            if a.x<b.x+b.w-0.5 and b.x<a.x+a.w-0.5 and a.y<b.y+b.h-0.5 and b.y<a.y+a.h-0.5 then return true end
        end
    end
    return false
end
Ring.overlapping=overlapping

-- anchors: {key=, x=, y=[, wx=, wy=]} mount points on screen (wx, wy: the direction to lean to); box: the weapon on screen {x1,y1,x2,y2};
-- area: where cells may go {x1,y1,x2,y2}; w,h: cell size; gap between cells. The ring is
-- centred in the area; its radii keep a cell's width (height) clear of the weapon where the
-- area allows. A tightly curved ring (a small screen) brings cells closer at its ends than their
-- distance along it: the step then grows until no two cells touch. Returns the cells
-- {key,x,y,w,h,ax,ay,lx,ly} (lx, ly: where the leader leaves the cell), the ring {cx,cy,rx,ry}
-- and whether the cells are all apart.
function Ring.layout(anchors,box,area,w,h,gap)
    local cx,cy=(area.x1+area.x2)/2,(area.y1+area.y2)/2
    local maxRx=math.max(w,(area.x2-area.x1-w)/2)
    local maxRy=math.max(h,(area.y2-area.y1-h)/2)
    local bw,bh=math.max(1,box.x2-box.x1),math.max(1,box.y2-box.y1)
    local rx=math.min(maxRx,math.max(bw/2+w*0.9,(area.x2-area.x1)*0.36))
    local ry=math.min(maxRy,math.max(bh/2+h*0.9,(area.y2-area.y1)*0.40))
    local ring={cx=cx,cy=cy,rx=rx,ry=ry}
    -- A mount point's direction from the weapon's centre, measured in the weapon's own box so
    -- that points above and below a long, thin weapon go to the top and bottom arcs.
    local bx,by=(box.x1+box.x2)/2,(box.y1+box.y2)/2
    local directions={}
    for i,a in ipairs(anchors) do
        -- wx, wy: where the cell should lean towards (the fitted part's own centre), else the point.
        local nx,ny=((a.wx or a.x)-bx)/(bw/2),((a.wy or a.y)-by)/(bh/2)
        if math.abs(nx)<1e-6 and math.abs(ny)<1e-6 then ny=-1 end
        directions[i]=math.atan2(ny,nx)
    end
    local cells
    for spread=1,1.8,0.1 do
        local e=sample(cx,cy,rx,ry,(math.max(w,h)+gap)*spread)
        if #anchors>math.floor(e.total+1e-6) then break end
        local wishes={}
        for i=1,#anchors do wishes[i]=uOf(e,directions[i]) end
        local spots=Ring.pack(wishes,e.total)
        cells={}
        for i,a in ipairs(anchors) do
            local x,y=pointOf(e,spots[i])
            local cell={key=a.key,x=x-w/2,y=y-h/2,w=w,h=h,ax=a.x,ay=a.y}
            cell.x=math.max(area.x1,math.min(area.x2-w,cell.x))
            cell.y=math.max(area.y1,math.min(area.y2-h,cell.y))
            cell.lx,cell.ly=Ring.edgePoint(cell,a.x,a.y)
            cells[#cells+1]=cell
        end
        if not overlapping(cells) then return cells,ring,true end
    end
    if not cells then
        -- Not even one turn of the ring holds them: evenly round it, they may touch.
        local e=sample(cx,cy,rx,ry,1)
        cells={}
        for i,a in ipairs(anchors) do
            local x,y=pointOf(e,(i-1)*e.total/math.max(1,#anchors))
            local cell={key=a.key,x=x-w/2,y=y-h/2,w=w,h=h,ax=a.x,ay=a.y}
            cell.lx,cell.ly=Ring.edgePoint(cell,a.x,a.y)
            cells[#cells+1]=cell
        end
    end
    return cells,ring,false
end

-- The largest square cell (from max down to min) whose ring keeps every cell apart.
function Ring.arrange(anchors,box,area,max,min,gapFactor)
    local size=max
    while true do
        local gap=math.floor(size*(gapFactor or 0.14)+0.5)
        local cells,ring,apart=Ring.layout(anchors,box,area,size,size,gap)
        if apart or size<=min then return cells,ring,size,apart end
        size=math.max(min,size-math.max(1,math.floor(size*0.05)))
    end
end

return Ring
