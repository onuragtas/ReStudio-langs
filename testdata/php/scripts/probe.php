<?php

// A small program for the debug adapter probe.
require __DIR__.'/../vendor/autoload.php';

use Illuminate\Support\Collection;

function total(array $prices): int
{
    $sum = 0;
    foreach ($prices as $price) {
        $sum += $price;
    }
    return $sum;
}

$prices = new Collection([10, 2, 5]);
echo total($prices->all()), PHP_EOL;
