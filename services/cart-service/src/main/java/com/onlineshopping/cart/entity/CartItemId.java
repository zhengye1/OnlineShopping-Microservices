package com.onlineshopping.cart.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * Composite PK for {@link CartItem} — required by JPA when using {@code @IdClass}.
 *
 * <p>Must (1) implement {@link Serializable}, (2) provide no-arg constructor,
 * (3) override equals/hashCode (done by Lombok {@code @EqualsAndHashCode}),
 * (4) field names + types must match the {@code @Id} fields on CartItem.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class CartItemId implements Serializable {

    private Long userId;
    private Long productId;
}
