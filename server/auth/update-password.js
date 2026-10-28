import {firebaseAuthRequest} from '../shared/firebase-runtime.js';
import {applyHttpHeaders,handleOptions,requireMethod,publicError} from '../shared/http.js';
export default async function handler(req,res){
  applyHttpHeaders(req,res,'POST, OPTIONS');
  if(handleOptions(req,res)||!requireMethod(req,res,'POST'))return;
  const code=String(req.body?.oobCode||''),password=String(req.body?.password||'');
  if(!code||password.length<8||password.length>128)return res.status(400).json({error:'A valid recovery code and password are required.'});
  try{await firebaseAuthRequest('resetPassword',{oobCode:code,newPassword:password});return res.json({message:'Password updated. Return to Morphly and sign in.'});}
  catch(error){return res.status(error.status||400).json({error:publicError(error,'Password reset failed.')});}
}
