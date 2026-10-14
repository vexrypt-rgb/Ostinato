/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils;

import baritone.api.utils.input.Input;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;

public class PlayerMovementInput extends ClientInput {

    private final InputOverrideHandler handler;

    PlayerMovementInput(InputOverrideHandler handler) {
        this.handler = handler;
    }

    @Override
    public void tick() {
        float[] analog = handler.analogMove();
        if (analog != null) {
            tickAnalog(analog[0], analog[1]);
            return;
        }
        float leftImpulse = 0.0F;
        float forwardImpulse = 0.0F;
        boolean jumping = handler.isInputForcedDown(Input.JUMP); // oppa gangnam style

        boolean up = handler.isInputForcedDown(Input.MOVE_FORWARD);
        if (up) {
            forwardImpulse++;
        }

        boolean down = handler.isInputForcedDown(Input.MOVE_BACK);
        if (down) {
            forwardImpulse--;
        }

        boolean left = handler.isInputForcedDown(Input.MOVE_LEFT);
        if (left) {
            leftImpulse++;
        }

        boolean right = handler.isInputForcedDown(Input.MOVE_RIGHT);
        if (right) {
            leftImpulse--;
        }

        boolean sneaking = handler.isInputForcedDown(Input.SNEAK) || handler.onMagma();
        if (sneaking) {
            leftImpulse *= 0.3D;
            forwardImpulse *= 0.3D;
        }
        this.moveVector = new Vec2(leftImpulse, forwardImpulse);

        boolean sprinting = handler.isInputForcedDown(Input.SPRINT);

        this.keyPresses = new net.minecraft.world.entity.player.Input(up, down, left, right, jumping, sneaking, sprinting);
    }

    /**
     * Ostinato: a thumbstick's worth of input, as Controlify feeds it: moveVector limited (not normalised) to length
     * 1, the direction booleans from the signs. Sneak is left to vanilla (modifyInput scales by SNEAKING_SPEED).
     */
    private void tickAnalog(float forward, float strafe) {
        float[] mv = AnalogMovement.moveVector(forward, strafe);
        this.moveVector = new Vec2(mv[0], mv[1]);
        boolean sneaking = handler.isInputForcedDown(Input.SNEAK) || handler.onMagma();
        this.keyPresses = new net.minecraft.world.entity.player.Input(mv[1] > 0, mv[1] < 0, mv[0] > 0, mv[0] < 0,
                handler.isInputForcedDown(Input.JUMP), sneaking, handler.isInputForcedDown(Input.SPRINT));
    }
}
