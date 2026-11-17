-- Common Sense lays out ammo images from native texture dimensions. Keep every
-- cartridge inside its HUD widget and, for VB, lay out from that widget instead.
local vbInstalled=false
local bbInstalled=false
local screenWidth=getCore():getScreenWidth()

local function installVB()
    if vbInstalled or type(VB_CS_GunStatsPanel)~='table' then return end
    local previous=VB_CS_GunStatsPanel.updateChildrenPos
    if type(previous)~='function' then return end
    vbInstalled=true

    VB_CS_GunStatsPanel.updateChildrenPos=function(self)
        local image=self.ammoImage
        if not image then return previous(self) end

        local scale=screenWidth/1920
        local size=math.ceil(30*scale)
        image.autoScale=true
        image.noAspect=false
        image:setWidth(size)
        image:setHeight(size)

        previous(self)

        if self.loadedAmmoLabel then
            self.loadedAmmoLabel:setX(image.x+image.width+math.ceil(15*scale))
        end
        if self.remainingAmmoLabel and self.loadedAmmoLabel then
            self.remainingAmmoLabel:setX(self.loadedAmmoLabel.x+self.loadedAmmoLabel.width+math.ceil(10*scale))
        end
    end
end

local function installBB()
    if bbInstalled then return end
    pcall(require,'ISUI/ISEquippedItem')
    if type(ISEquippedItem)~='table' or type(ISEquippedItem.prerender)~='function' then return end
    bbInstalled=true
    local previous=ISEquippedItem.prerender

    ISEquippedItem.prerender=function(self)
        previous(self)
        local assets=self.BB_CS_UIAssets
        local image=assets and assets[1]
        if not image then return end
        image.autoScale=true
        image.noAspect=false
        if self.mainHand then
            image:setWidth(self.mainHand:getWidth()/3)
            image:setHeight(self.mainHand:getHeight()/3)
            image:setX(self.mainHand:getX()+48)
            image:setY(self.mainHand:getY()+8)
        end
    end
end

installVB()
installBB()
Events.OnGameStart.Add(installVB)
Events.OnGameStart.Add(installBB)
