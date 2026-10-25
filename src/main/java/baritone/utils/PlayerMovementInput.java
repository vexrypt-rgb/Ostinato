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

        float[] analog = handler.takeAnalog();
        if (analog != null) {
            // Ostinato: a controller stick, not keys. The client's own input chain (0.98, sneak, the square-to-circle
            // map) applies to it as it does to a gamepad's.
            leftImpulse = HumanInputCaps.clampAxis(analog[0]); // Ostinato: a real stick stops at full tilt
            forwardImpulse = HumanInputCaps.clampAxis(analog[1]);
            up = forwardImpulse > 0;
            down = forwardImpulse < 0;
            left = leftImpulse > 0;
            right = leftImpulse < 0;
        }

        // Ostinato: no 0.3 here. LocalPlayer.modifyInput scales by the sneaking-speed attribute itself, so scaling here
        // too made a sneak walk 0.09: on ice it barely pushed, and a landing that slid a block past stood there (cubed).
        boolean sneaking = handler.isInputForcedDown(Input.SNEAK) || handler.onMagma();
        this.moveVector = new Vec2(leftImpulse, forwardImpulse);

        boolean sprinting = handler.isInputForcedDown(Input.SPRINT);

        this.keyPresses = new net.minecraft.world.entity.player.Input(up, down, left, right, jumping, sneaking, sprinting);
    }
}
