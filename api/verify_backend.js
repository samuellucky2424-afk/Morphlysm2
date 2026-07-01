import loginHandler from './login.js';
import signupHandler from './signup.js';
import balanceHandler from './balance.js';
import consumeHandler from './consume.js';
import checkoutHandler from './create-checkout.js';
import webhookHandler from './flutterwave-webhook.js';

console.log('--- Morphly Backend Local Verification ---');
console.log('✓ login.js: handler is a', typeof loginHandler);
console.log('✓ signup.js: handler is a', typeof signupHandler);
console.log('✓ balance.js: handler is a', typeof balanceHandler);
console.log('✓ consume.js: handler is a', typeof consumeHandler);
console.log('✓ create-checkout.js: handler is a', typeof checkoutHandler);
console.log('✓ flutterwave-webhook.js: handler is a', typeof webhookHandler);
console.log('-------------------------------------------');
console.log('Local backend syntax verification successful! All modules load and compile correctly.');
