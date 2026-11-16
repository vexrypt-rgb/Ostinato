| Scenario | Config | plugins enabled | real client hits | protocol bot hits | bot-only checks | end real / bot |
|---|---|---|---|---|---|---|
| pillar | none | - | none | none | none | [5.5, -54.0, -3.5] / [5.5, -54.0, -3.5] |
| pillar | grim | GrimAC | none | none | none | [5.5, -54.0, -3.5] / [5.5, -54.0, -3.5] |
| pillar | grim+pl | ProtocolLib+GrimAC | none | none | none | [5.5, -54.0, -3.5] / [5.5, -54.0, -3.5] |
| pillar | ncp | NoCheatPlus | ncp:blockinteract.direction 6, ncp:blockplace.noswing 5 | ncp:blockplace.noswing 5 | none | [5.5, -54.0, -3.5] / [5.5, -54.0, -3.5] |
| pillar | ncp+pl | ProtocolLib+NoCheatPlus | ncp:blockinteract.direction 6, ncp:blockplace.noswing 5 | ncp:blockplace.noswing 5 | none | [5.5, -54.0, -3.5] / [5.5, -54.0, -3.5] |
| pillar | spartan | Spartan | spartan:gravity-simulation 16 | spartan:gravity-simulation 16 | none | [5.5, -54.0, -3.5] / [5.5, -54.0, -3.5] |
| pillar | spartan+pl | ProtocolLib+Spartan | none | spartan:speed-simulation 1 | spartan:speed-simulation 1 | [5.5, -54.0, -3.5] / [5.5, -54.0, -3.5] |
| pillar | lac | LightAntiCheat | none | none | none | [5.5, -54.0, -3.5] / ? |
| pillar | lac+pl | ProtocolLib+LightAntiCheat | none | kick:Internal server error 1 | kick:Internal server error 1 | [5.5, -54.0, -3.5] / ? |
| walk | none | - | none | none | none | [20.567596161168954, -60.0, 0.4855230077654208] / [20.685483336316857, -60.0, 0.4998621632321877] |
| walk | grim | GrimAC | none | none | none | [20.60052185229206, -60.0, 0.5005624388088845] / [20.676002882019212, -60.0, 0.4989465916334661] |
| walk | grim+pl | ProtocolLib+GrimAC | none | none | none | [20.41879703042727, -60.0, 0.5005107783966144] / [20.692206257137215, -60.0, 0.5000293591715945] |
| walk | ncp | NoCheatPlus | ncp:player.survivalfly 92 | ncp:player.survivalfly 79 | none | [20.49924829769245, -60.0, 0.50026547886857] / [20.612099761480575, -60.0, 0.501770579612234] |
| walk | ncp+pl | ProtocolLib+NoCheatPlus | ncp:player.survivalfly 1205 | ncp:player.survivalfly 451 | none | [3.9828697730667386, -60.0, 0.6211676590073674] / [20.56868581814534, -60.0, 0.51696340777523] |
| walk | spartan | Spartan | none | none | none | [20.585681652465556, -60.0, 0.5004482417277303] / [20.69942701598258, -60.0, 0.5000000622649369] |
| walk | spartan+pl | ProtocolLib+Spartan | none | none | none | [20.41890548102746, -60.0, 0.49997295455642626] / [20.681272545901926, -60.0, 0.500029260440792] |
| walk | lac | LightAntiCheat | none | none | none | [20.399273504048374, -60.0, 0.4383137404571022] / [20.67783424593681, -60.0, 0.4997244281653319] |
| walk | lac+pl | ProtocolLib+LightAntiCheat | none | none | none | [20.58585432999485, -60.0, 0.49989268024923283] / [20.689015023846956, -60.0, 0.5000358915416965] |
| parkour | none | - | none | none | none | [8.371163421966818, -60.0, 0.4993193453694019] / [8.684742656615807, -60.0, 0.5152594011927935] |
| parkour | grim | GrimAC | none | none | none | [8.369826496896202, -60.0, 0.5135009773942047] / [8.637381527067923, -60.0, 0.48701539187920295] |
| parkour | grim+pl | ProtocolLib+GrimAC | none | none | none | [8.359406120914452, -60.0, 0.4668301494192233] / [8.636837205522546, -60.0, 0.48733039110413556] |
| parkour | ncp | NoCheatPlus | ncp:blockbreak.noswing 2, ncp:player.survivalfly 10 | ncp:blockbreak.noswing 2 | none | [8.361642710980444, -60.0, 0.5153536275328723] / [8.64503106118559, -60.0, 0.5112767415242605] |
| parkour | ncp+pl | ProtocolLib+NoCheatPlus | ncp:blockbreak.noswing 2 | ncp:blockbreak.noswing 2 | none | [8.362425357735347, -60.0, 0.4712992490128044] / [8.678307383482858, -60.0, 0.5179540469810888] |
| parkour | spartan | Spartan | spartan:gravity-simulation 3 | spartan:gravity-simulation 6 | none | [8.371028945610496, -60.0, 0.5076026708397743] / [8.646698645796736, -60.0, 0.4891524141926491] |
| parkour | spartan+pl | ProtocolLib+Spartan | none | none | none | [8.408926866070777, -60.0, 0.49900392898939894] / [8.63660073162025, -60.0, 0.4875032874053094] |
| parkour | lac | LightAntiCheat | kick:Server closed 1 | none | none | ? / [8.63971346677374, -60.0, 0.4876004215447727] |
| parkour | lac+pl | ProtocolLib+LightAntiCheat | none | none | none | ? / [8.650746504895931, -60.0, 0.5004552420497689] |
| ladder | none | - | none | none | none | [4.447719865660587, -54.0, 0.510864764129128] / [4.470041434635022, -54.0, 0.49995856620433526] |
| ladder | grim | GrimAC | none | none | none | [4.447719865660587, -54.0, 0.510864764129128] / [4.470041364796509, -54.0, 0.49995581118203225] |
| ladder | grim+pl | ProtocolLib+GrimAC | none | none | none | [4.447719865660587, -54.0, 0.510864764129128] / [4.470041434635022, -54.0, 0.4999585184277288] |
| ladder | ncp | NoCheatPlus | none | kick:cl(!)7 Moved unexpectedly. 1, ncp:player.survivalfly 2077 | kick:cl(!)7 Moved unexpectedly. 1, ncp:player.survivalfly 2077 | [4.447719865660587, -54.0, 0.510864764129128] / ? |
| ladder | ncp+pl | ProtocolLib+NoCheatPlus | none | kick:cl(!)7 Moved unexpectedly. 1, ncp:player.survivalfly 2077 | kick:cl(!)7 Moved unexpectedly. 1, ncp:player.survivalfly 2077 | ? / ? |
| ladder | spartan | Spartan | spartan:gravity-simulation 6 | spartan:gravity-simulation 4 | none | [4.447719865660587, -54.0, 0.510864764129128] / [4.470021300217057, -54.0, 0.5002191325032725] |
| ladder | spartan+pl | ProtocolLib+Spartan | none | none | none | [4.447719865660587, -54.0, 0.510864764129128] / [4.470040534132186, -54.0, 0.49994018176496113] |
| ladder | lac | LightAntiCheat | lac:FastClimbA (ClimbingSpeed) 1, lac:TimerA (MovementTimer) 1 | none | none | [4.447719865660587, -54.0, 0.510864764129128] / ? |
| ladder | lac+pl | ProtocolLib+LightAntiCheat | lac:FastClimbA (ClimbingSpeed) 2 | lac:FastClimbA (ClimbingSpeed) 2 | none | [4.447719865660587, -54.0, 0.510864764129128] / [4.470041618536899, -54.0, 0.4999652129188768] |
| bridge | none | - | none | none | none | [9.675645773837818, -60.0, 0.45926145834124626] / [9.690236824236907, -60.0, 0.5005057870748535] |
| bridge | grim | GrimAC | none | none | none | [9.340265825598452, -60.0, 0.5808351504961067] / [9.687140153080477, -60.0, 0.5082718682069539] |
| bridge | grim+pl | ProtocolLib+GrimAC | none | none | none | [9.57347337845758, -60.0, 0.5370534410909047] / [9.686569195252334, -60.0, 0.5105858360855524] |
| bridge | ncp | NoCheatPlus | ncp:blockinteract.direction 1, ncp:player.survivalfly 32 | ncp:player.survivalfly 19 | none | [9.371642528361821, -60.0, 0.4898421130389697] / [9.54289606120563, -60.0, 0.4710294376397004] |
| bridge | ncp+pl | ProtocolLib+NoCheatPlus | ncp:blockinteract.direction 1, ncp:player.survivalfly 97 | ncp:player.survivalfly 19 | none | [9.598039305724773, -60.0, 0.494014578609101] / [9.689370242182177, -60.0, 0.5033625066958975] |
| bridge | spartan | Spartan | spartan:gravity-simulation 7 | spartan:gravity-simulation 5 | none | [9.682348512249444, -60.0, 0.362369043534829] / [9.642974706457725, -60.0, 0.5113577085106604] |
| bridge | spartan+pl | ProtocolLib+Spartan | none | none | none | [9.634316201445342, -60.0, 0.4665657030662498] / [9.688745230405088, -60.0, 0.5078183063087472] |
| bridge | lac | LightAntiCheat | none | none | none | [9.54362672546664, -60.0, 0.1514503613602297] / ? |
| bridge | lac+pl | ProtocolLib+LightAntiCheat | none | none | none | [9.526421861220154, -60.0, 0.5276525793198972] / ? |
| voidbridge | none | - | none | none | none | [9.863022617113502, -60.0, 0.527172720546294] / [9.604371585588668, -60.0, 0.5] |
| voidbridge | grim | GrimAC | none | none | none | [9.57869655872471, -60.0, 0.5000122234852716] / [9.604371585588668, -60.0, 0.5] |
| voidbridge | grim+pl | ProtocolLib+GrimAC | none | none | none | [9.863022617113502, -60.0, 0.527172720546294] / [9.604371585588668, -60.0, 0.5] |
| voidbridge | ncp | NoCheatPlus | ncp:blockinteract.direction 1, ncp:blockplace.noswing 1 | ncp:blockplace.noswing 4, ncp:blockplace.scaffold 5 | ncp:blockplace.scaffold 5 | [0.5, -60.0, 0.5] / [9.587280016461852, -60.0, 0.5] |
| voidbridge | ncp+pl | ProtocolLib+NoCheatPlus | ncp:blockinteract.direction 1, ncp:blockplace.noswing 1, ncp:net.moving 1 | ncp:blockplace.noswing 4, ncp:blockplace.scaffold 5 | ncp:blockplace.scaffold 5 | [0.5, -60.0, 0.5] / [9.587280016461852, -60.0, 0.5] |
| voidbridge | spartan | Spartan | none | none | none | [9.863022617113502, -60.0, 0.527172720546294] / [9.604371585588668, -60.0, 0.5] |
| voidbridge | spartan+pl | ProtocolLib+Spartan | none | none | none | [9.680664768006643, -60.0, 0.6383859557117786] / [9.604370950318161, -60.0, 0.5000009430091726] |
| voidbridge | lac | LightAntiCheat | none | none | none | [9.58202328104402, -60.0, 0.5125790979579251] / ? |
| voidbridge | lac+pl | ProtocolLib+LightAntiCheat | none | none | none | [9.58202328104402, -60.0, 0.5125790979579251] / ? |
| mine | none | - | none | none | none | [-102.74155573062049, -60.0, 3.500023954745809] / [4.481232435905056, -60.0, 3.4797811729006005] |
| mine | grim | GrimAC | none | none | none | [-103.62299425423166, -60.0, 3.4998818053687955] / [4.482030503798529, -60.0, 3.4796751035112807] |
| mine | grim+pl | ProtocolLib+GrimAC | none | none | none | ? / [4.480401781054053, -60.0, 3.4779936225764] |
| mine | ncp | NoCheatPlus | ncp:blockbreak.noswing 3, ncp:player.survivalfly 641 | none | none | [-32.639889373992595, -60.0, 3.5001190229206647] / [4.480592771871116, -60.0, 3.48054186992974] |
| mine | ncp+pl | ProtocolLib+NoCheatPlus | ncp:blockbreak.noswing 3, ncp:player.survivalfly 778 | none | none | [-25.99108357597589, -60.0, 3.499910025420379] / [4.482536809221442, -60.0, 3.4730991010600163] |
| mine | spartan | Spartan | spartan:exploits 4, spartan:gravity-simulation 1 | none | none | [-102.39134636814512, -60.0, 3.4999510856057316] / [4.482378284589627, -60.0, 3.4776072719630196] |
| mine | spartan+pl | ProtocolLib+Spartan | spartan:exploits 4 | none | none | [-102.38477879110772, -60.0, 3.4997851834088305] / [4.478970475125416, -60.0, 3.48334353035842] |
| mine | lac | LightAntiCheat | none | none | none | [-102.39134636814512, -60.0, 3.4999510856057316] / [4.478106233344351, -60.0, 3.483924471119791] |
| mine | lac+pl | ProtocolLib+LightAntiCheat | none | none | none | [-104.89749220023492, -60.0, 3.499891208120068] / ? |
